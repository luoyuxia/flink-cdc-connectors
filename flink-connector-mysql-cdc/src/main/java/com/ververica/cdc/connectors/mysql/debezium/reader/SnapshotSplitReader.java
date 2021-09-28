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

import com.ververica.cdc.connectors.mysql.debezium.dispatcher.SignalEventDispatcher;
import com.ververica.cdc.connectors.mysql.debezium.task.MySqlBinlogSplitReadTask;
import com.ververica.cdc.connectors.mysql.debezium.task.MySqlSnapshotSplitReadTask;
import com.ververica.cdc.connectors.mysql.debezium.task.context.StatefulTaskContext;
import com.ververica.cdc.connectors.mysql.source.offset.BinlogOffset;
import com.ververica.cdc.connectors.mysql.source.split.MySqlBinlogSplit;
import com.ververica.cdc.connectors.mysql.source.split.MySqlSnapshotSplit;
import com.ververica.cdc.connectors.mysql.source.split.MySqlSplit;
import com.ververica.cdc.connectors.mysql.source.utils.RecordUtils;
import io.debezium.config.Configuration;
import io.debezium.connector.base.ChangeEventQueue;
import io.debezium.connector.mysql.MySqlConnectorConfig;
import io.debezium.connector.mysql.MySqlOffsetContext;
import io.debezium.connector.mysql.MySqlStreamingChangeEventSourceMetrics;
import io.debezium.pipeline.DataChangeEvent;
import io.debezium.pipeline.source.spi.ChangeEventSource;
import io.debezium.pipeline.spi.SnapshotResult;
import io.debezium.util.SchemaNameAdjuster;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.ververica.cdc.connectors.mysql.source.utils.RecordUtils.normalizedSplitRecords;

/**
 * A snapshot reader that reads data from Table in split level, the split is assigned by primary key
 * range.
 */
public class SnapshotSplitReader implements DebeziumReader<SourceRecord, MySqlSplit> {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotSplitReader.class);
    private final StatefulTaskContext statefulTaskContext;
    private final ExecutorService executor;
    private final MySqlSnapshotSplit snapshotSplit;

    private volatile ChangeEventQueue<DataChangeEvent> queue;
    private volatile boolean currentTaskRunning;
    private volatile Throwable readException;

    // task to read snapshot for current split
    private MySqlSnapshotSplitReadTask splitSnapshotReadTask;
    private SchemaNameAdjuster nameAdjuster;
    public AtomicBoolean hasNextElement;

    public SnapshotSplitReader(
            StatefulTaskContext statefulTaskContext,
            MySqlSnapshotSplit snapshotSplit,
            ExecutorService executor) {
        this.statefulTaskContext = statefulTaskContext;
        this.snapshotSplit = snapshotSplit;
        this.executor = executor;
        this.currentTaskRunning = false;
        this.hasNextElement = new AtomicBoolean(false);
    }

    public void start() {
        statefulTaskContext.configure(snapshotSplit);
        this.queue = statefulTaskContext.getQueue();
        this.nameAdjuster = statefulTaskContext.getSchemaNameAdjuster();
        this.hasNextElement.set(true);
        this.splitSnapshotReadTask =
                new MySqlSnapshotSplitReadTask(
                        statefulTaskContext.getConnectorConfig(),
                        statefulTaskContext.getOffsetContext(),
                        statefulTaskContext.getSnapshotChangeEventSourceMetrics(),
                        statefulTaskContext.getDatabaseSchema(),
                        statefulTaskContext.getConnection(),
                        statefulTaskContext.getEventDispatcher(),
                        statefulTaskContext.getTopicSelector(),
                        StatefulTaskContext.getClock(),
                        snapshotSplit);
        executor.submit(
                () -> {
                    try {
                        currentTaskRunning = true;
                        // execute snapshot read task
                        final SnapshotSplitChangeEventSourceContextImpl sourceContext =
                                new SnapshotSplitChangeEventSourceContextImpl();
                        SnapshotResult snapshotResult =
                                splitSnapshotReadTask.execute(sourceContext);

                        // execute binlog read task
                        if (snapshotResult.isCompletedOrSkipped()) {
                            final MySqlBinlogSplit appendBinlogSplit =
                                    createBinlogSplit(sourceContext);
                            // if low watermark = high watermark, no need to read
                            // binlog between low watermark and high watermark, can return directly,
                            // but still need to send binlog end event to mark the end of snapshot
                            if (sourceContext
                                    .getLowWatermark()
                                    .equals(sourceContext.getHighWatermark())) {
                                BinlogOffset binlogEndOffset =
                                        new BinlogOffset(
                                                appendBinlogSplit.getEndingOffset().getFilename(),
                                                appendBinlogSplit.getEndingOffset().getPosition());
                                final SignalEventDispatcher signalEventDispatcher =
                                        statefulTaskContext.getSignalEventDispatcher();
                                signalEventDispatcher.dispatchWatermarkEvent(
                                        appendBinlogSplit,
                                        binlogEndOffset,
                                        SignalEventDispatcher.WatermarkKind.BINLOG_END);
                                currentTaskRunning = false;
                                LOG.info(
                                        "Low watermark is equal to high watermark in snapshot phase, skip reading binlog"
                                                + " between low watermark and high watermark");
                                return;
                            }
                            final MySqlOffsetContext mySqlOffsetContext =
                                    statefulTaskContext.getOffsetContext();
                            mySqlOffsetContext.setBinlogStartPoint(
                                    appendBinlogSplit.getStartingOffset().getFilename(),
                                    appendBinlogSplit.getStartingOffset().getPosition());
                            // we should only capture events for the current table,
                            // otherwise, we may can't find corresponding schema
                            Configuration dezConf =
                                    statefulTaskContext
                                            .getDezConf()
                                            .edit()
                                            .with("table.whitelist", snapshotSplit.getTableId())
                                            .build();
                            // task to read binlog for current split
                            MySqlBinlogSplitReadTask splitBinlogReadTask =
                                    new MySqlBinlogSplitReadTask(
                                            new MySqlConnectorConfig(dezConf),
                                            mySqlOffsetContext,
                                            statefulTaskContext.getConnection(),
                                            statefulTaskContext.getEventDispatcher(),
                                            statefulTaskContext.getSignalEventDispatcher(),
                                            statefulTaskContext.getErrorHandler(),
                                            StatefulTaskContext.getClock(),
                                            statefulTaskContext.getTaskContext(),
                                            (MySqlStreamingChangeEventSourceMetrics)
                                                    statefulTaskContext
                                                            .getStreamingChangeEventSourceMetrics(),
                                            appendBinlogSplit);
                            splitBinlogReadTask.execute(
                                    new SnapshotBinlogSplitChangeEventSourceContextImpl());
                        } else {
                            readException =
                                    new IllegalStateException(
                                            String.format(
                                                    "Read snapshot for mysql split %s fail",
                                                    snapshotSplit));
                        }
                    } catch (Exception e) {
                        currentTaskRunning = false;
                        LOG.error(
                                String.format(
                                        "Execute snapshot read task for mysql split %s fail",
                                        snapshotSplit),
                                e);
                        readException = e;
                    }
                });
    }

    private MySqlBinlogSplit createBinlogSplit(
            SnapshotSplitChangeEventSourceContextImpl sourceContext) {
        return new MySqlBinlogSplit(
                snapshotSplit.splitId(),
                snapshotSplit.getSplitKeyType(),
                sourceContext.getLowWatermark(),
                sourceContext.getHighWatermark(),
                new ArrayList<>(),
                snapshotSplit.getTableSchemas());
    }

    @Override
    public boolean isFinished() {
        return !currentTaskRunning && !hasNextElement.get();
    }

    @Nullable
    @Override
    public Iterator<SourceRecord> pollSplitRecords() throws InterruptedException {
        checkReadException();

        if (hasNextElement.get()) {
            // data input: [low watermark event][snapshot events][high watermark event][binlog
            // events][binlog-end event]
            // data output: [low watermark event][normalized events][high watermark event]
            boolean reachBinlogEnd = false;
            final List<SourceRecord> sourceRecords = new ArrayList<>();
            while (!reachBinlogEnd) {
                List<DataChangeEvent> batch = queue.poll();
                for (DataChangeEvent event : batch) {
                    sourceRecords.add(event.getRecord());
                    if (RecordUtils.isEndWatermarkEvent(event.getRecord())) {
                        reachBinlogEnd = true;
                        break;
                    }
                }
            }
            // snapshot split return its data once
            hasNextElement.set(false);
            return normalizedSplitRecords(snapshotSplit, sourceRecords, nameAdjuster).iterator();
        }
        return null;
    }

    private void checkReadException() {
        if (readException != null) {
            throw new FlinkRuntimeException(
                    String.format(
                            "Read split %s error due to %s.",
                            snapshotSplit, readException.getMessage()),
                    readException);
        }
    }

    @Override
    public void close() {
        LOG.info("Close snapshot split reader for {}", snapshotSplit);
    }

    /**
     * {@link ChangeEventSource.ChangeEventSourceContext} implementation that keeps low/high
     * watermark for each {@link MySqlSnapshotSplit}.
     */
    public class SnapshotSplitChangeEventSourceContextImpl
            implements ChangeEventSource.ChangeEventSourceContext {

        private BinlogOffset lowWatermark;
        private BinlogOffset highWatermark;

        public BinlogOffset getLowWatermark() {
            return lowWatermark;
        }

        public void setLowWatermark(BinlogOffset lowWatermark) {
            this.lowWatermark = lowWatermark;
        }

        public BinlogOffset getHighWatermark() {
            return highWatermark;
        }

        public void setHighWatermark(BinlogOffset highWatermark) {
            this.highWatermark = highWatermark;
        }

        @Override
        public boolean isRunning() {
            return lowWatermark != null && highWatermark != null;
        }
    }

    /**
     * The {@link ChangeEventSource.ChangeEventSourceContext} implementation for bounded binlog task
     * of a snapshot split task.
     */
    public class SnapshotBinlogSplitChangeEventSourceContextImpl
            implements ChangeEventSource.ChangeEventSourceContext {

        public void finished() {
            currentTaskRunning = false;
        }

        @Override
        public boolean isRunning() {
            return currentTaskRunning;
        }
    }
}
