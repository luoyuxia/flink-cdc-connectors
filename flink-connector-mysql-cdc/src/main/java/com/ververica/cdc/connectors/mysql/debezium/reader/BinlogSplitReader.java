/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.cdc.connectors.mysql.debezium.reader;

import org.apache.flink.util.FlinkRuntimeException;

import com.ververica.cdc.connectors.mysql.debezium.task.MySqlBinlogSplitReadTask;
import com.ververica.cdc.connectors.mysql.debezium.task.context.StatefulTaskContext;
import com.ververica.cdc.connectors.mysql.source.offset.BinlogOffset;
import com.ververica.cdc.connectors.mysql.source.split.FinishedSnapshotSplitInfo;
import com.ververica.cdc.connectors.mysql.source.split.MySqlBinlogSplit;
import com.ververica.cdc.connectors.mysql.source.split.MySqlSplit;
import com.ververica.cdc.connectors.mysql.source.utils.RecordUtils;
import io.debezium.connector.base.ChangeEventQueue;
import io.debezium.connector.mysql.MySqlOffsetContext;
import io.debezium.connector.mysql.MySqlStreamingChangeEventSourceMetrics;
import io.debezium.pipeline.DataChangeEvent;
import io.debezium.pipeline.source.spi.ChangeEventSource;
import io.debezium.relational.TableId;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static com.ververica.cdc.connectors.mysql.source.utils.RecordUtils.getBinlogPosition;
import static com.ververica.cdc.connectors.mysql.source.utils.RecordUtils.getSplitKey;
import static com.ververica.cdc.connectors.mysql.source.utils.RecordUtils.getTableId;
import static com.ververica.cdc.connectors.mysql.source.utils.RecordUtils.isDataChangeRecord;

/**
 * A Debezium binlog reader implementation that also support reads binlog and filter overlapping
 * snapshot data that {@link SnapshotSplitReader} read.
 */
public class BinlogSplitReader implements DebeziumReader<SourceRecord, MySqlSplit> {

    private static final Logger LOG = LoggerFactory.getLogger(BinlogSplitReader.class);
    private final StatefulTaskContext statefulTaskContext;
    private final ExecutorService executor;
    private final MySqlBinlogSplit binlogSplit;

    private volatile ChangeEventQueue<DataChangeEvent> queue;
    private volatile boolean currentTaskRunning;
    private volatile Throwable readException;

    private MySqlBinlogSplitReadTask binlogSplitReadTask;
    private Map<TableId, List<FinishedSnapshotSplitInfo>> finishedSplitsInfo;
    // tableId -> the max splitHighWatermark
    private Map<TableId, BinlogOffset> maxSplitHighWatermarkMap;

    public BinlogSplitReader(
            StatefulTaskContext statefulTaskContext,
            MySqlBinlogSplit binlogSplit,
            ExecutorService executor) {
        this.binlogSplit = binlogSplit;
        this.statefulTaskContext = statefulTaskContext;
        this.executor = executor;
        this.currentTaskRunning = false;
    }

    public void start() {
        configureFilter();
        statefulTaskContext.configure(binlogSplit);
        this.queue = statefulTaskContext.getQueue();
        final MySqlOffsetContext mySqlOffsetContext = statefulTaskContext.getOffsetContext();
        mySqlOffsetContext.setBinlogStartPoint(
                binlogSplit.getStartingOffset().getFilename(),
                binlogSplit.getStartingOffset().getPosition());
        this.binlogSplitReadTask =
                new MySqlBinlogSplitReadTask(
                        statefulTaskContext.getConnectorConfig(),
                        mySqlOffsetContext,
                        statefulTaskContext.getConnection(),
                        statefulTaskContext.getEventDispatcher(),
                        statefulTaskContext.getSignalEventDispatcher(),
                        statefulTaskContext.getErrorHandler(),
                        StatefulTaskContext.getClock(),
                        statefulTaskContext.getTaskContext(),
                        (MySqlStreamingChangeEventSourceMetrics)
                                statefulTaskContext.getStreamingChangeEventSourceMetrics(),
                        binlogSplit);

        executor.submit(
                () -> {
                    try {
                        currentTaskRunning = true;
                        binlogSplitReadTask.execute(new BinlogSplitChangeEventSourceContextImpl());
                    } catch (Exception e) {
                        currentTaskRunning = false;
                        LOG.error(
                                String.format(
                                        "Execute binlog read task for mysql split %s fail",
                                        binlogSplit),
                                e);
                        readException = e;
                    }
                });
    }

    private class BinlogSplitChangeEventSourceContextImpl
            implements ChangeEventSource.ChangeEventSourceContext {
        @Override
        public boolean isRunning() {
            return currentTaskRunning;
        }
    }

    @Override
    public boolean isFinished() {
        return binlogSplit == null || !currentTaskRunning;
    }

    @Nullable
    @Override
    public Iterator<SourceRecord> pollSplitRecords() throws InterruptedException {
        checkReadException();
        final List<SourceRecord> sourceRecords = new ArrayList<>();
        if (currentTaskRunning) {
            List<DataChangeEvent> batch = queue.poll();
            for (DataChangeEvent event : batch) {
                if (shouldEmit(event.getRecord())) {
                    sourceRecords.add(event.getRecord());
                }
            }
        }
        return sourceRecords.iterator();
    }

    private void checkReadException() {
        if (readException != null) {
            throw new FlinkRuntimeException(
                    String.format(
                            "Read split %s error due to %s.",
                            binlogSplit, readException.getMessage()),
                    readException);
        }
    }

    @Override
    public void close() {
        LOG.info("Close binlog split reader for {}", binlogSplit);
    }

    /**
     * Returns the record should emit or not.
     *
     * <p>The watermark signal algorithm is the binlog split reader only sends the binlog event that
     * belongs to its finished snapshot splits. For each snapshot split, the binlog event is valid
     * since the offset is after its high watermark.
     *
     * <pre> E.g: the data input is :
     *    snapshot-split-0 info : [0,    1024) highWatermark0
     *    snapshot-split-1 info : [1024, 2048) highWatermark1
     *  the data output is:
     *  only the binlog event belong to [0,    1024) and offset is after highWatermark0 should send,
     *  only the binlog event belong to [1024, 2048) and offset is after highWatermark1 should send.
     * </pre>
     */
    private boolean shouldEmit(SourceRecord sourceRecord) {
        if (isDataChangeRecord(sourceRecord)) {
            TableId tableId = getTableId(sourceRecord);
            BinlogOffset position = getBinlogPosition(sourceRecord);
            // aligned, all snapshot splits of the table has reached max highWatermark
            if (position.isAtOrBefore(maxSplitHighWatermarkMap.get(tableId))) {
                return true;
            }
            Object[] key =
                    getSplitKey(
                            binlogSplit.getSplitKeyType(),
                            sourceRecord,
                            statefulTaskContext.getSchemaNameAdjuster());
            for (FinishedSnapshotSplitInfo splitInfo : finishedSplitsInfo.get(tableId)) {
                if (RecordUtils.splitKeyRangeContains(
                                key, splitInfo.getSplitStart(), splitInfo.getSplitEnd())
                        && position.isAtOrBefore(splitInfo.getHighWatermark())) {
                    return true;
                }
            }
            // not in the monitored splits scope, do not emit
            return false;
        }
        // always send the schema change event and signal event
        // we need record them to state of Flink
        return true;
    }

    private void configureFilter() {
        List<FinishedSnapshotSplitInfo> finishedSplitInfos =
                binlogSplit.getFinishedSnapshotSplitInfos();
        Map<TableId, List<FinishedSnapshotSplitInfo>> splitsInfoMap = new HashMap<>();
        Map<TableId, BinlogOffset> tableIdBinlogPositionMap = new HashMap<>();
        if (finishedSplitInfos.isEmpty()) {
            for (TableId tableId : binlogSplit.getTableSchemas().keySet()) {
                tableIdBinlogPositionMap.put(tableId, binlogSplit.getStartingOffset());
            }
        } else {
            for (FinishedSnapshotSplitInfo finishedSplitInfo : finishedSplitInfos) {
                TableId tableId = finishedSplitInfo.getTableId();
                List<FinishedSnapshotSplitInfo> list =
                        splitsInfoMap.getOrDefault(tableId, new ArrayList<>());
                list.add(finishedSplitInfo);
                splitsInfoMap.put(tableId, list);

                BinlogOffset highWatermark = finishedSplitInfo.getHighWatermark();
                BinlogOffset maxHighWatermark = tableIdBinlogPositionMap.get(tableId);
                if (maxHighWatermark == null || highWatermark.isAtOrBefore(maxHighWatermark)) {
                    tableIdBinlogPositionMap.put(tableId, highWatermark);
                }
            }
        }
        this.finishedSplitsInfo = splitsInfoMap;
        this.maxSplitHighWatermarkMap = tableIdBinlogPositionMap;
    }
}
