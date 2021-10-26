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

package com.ververica.cdc.connectors.mysql.source.assigners;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.types.logical.RowType;

import com.ververica.cdc.connectors.mysql.MySqlValidator;
import com.ververica.cdc.connectors.mysql.schema.MySqlSchema;
import com.ververica.cdc.connectors.mysql.source.assigners.state.BinlogPendingSplitsState;
import com.ververica.cdc.connectors.mysql.source.assigners.state.PendingSplitsState;
import com.ververica.cdc.connectors.mysql.source.config.MySqlSourceConfig;
import com.ververica.cdc.connectors.mysql.source.offset.BinlogOffset;
import com.ververica.cdc.connectors.mysql.source.split.MySqlBinlogSplit;
import com.ververica.cdc.connectors.mysql.source.split.MySqlSplit;
import io.debezium.connector.mysql.MySqlConnection;
import io.debezium.relational.RelationalTableFilters;
import io.debezium.relational.TableId;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.ververica.cdc.connectors.mysql.debezium.DebeziumUtils.closeMySqlConnection;
import static com.ververica.cdc.connectors.mysql.debezium.DebeziumUtils.currentBinlogOffset;
import static com.ververica.cdc.connectors.mysql.debezium.DebeziumUtils.openMySqlConnection;
import static com.ververica.cdc.connectors.mysql.source.utils.TableDiscoveryUtils.discoverCapturedTables;
import static org.apache.flink.table.api.DataTypes.FIELD;
import static org.apache.flink.table.api.DataTypes.ROW;

/**
 * A {@link MySqlSplitAssigner} which only read binlog from current binlog position.
 *
 * <p>TODO: the table and schema discovery should happen in split reader instead of here, to reduce
 * the split size.
 */
public class MySqlBinlogSplitAssigner implements MySqlSplitAssigner {
    private static final String BINLOG_SPLIT_ID = "binlog-split";

    private final MySqlSourceConfig sourceConfig;
    private final RelationalTableFilters tableFilters;
    private final MySqlValidator validator;

    private MySqlConnection jdbc;
    private boolean isBinlogSplitAssigned;

    public MySqlBinlogSplitAssigner(MySqlSourceConfig sourceConfig, MySqlValidator validator) {
        this(sourceConfig, false, validator);
    }

    public MySqlBinlogSplitAssigner(
            MySqlSourceConfig sourceConfig,
            BinlogPendingSplitsState checkpoint,
            MySqlValidator validator) {
        this(sourceConfig, checkpoint.isBinlogSplitAssigned(), validator);
    }

    private MySqlBinlogSplitAssigner(
            MySqlSourceConfig sourceConfig,
            boolean isBinlogSplitAssigned,
            MySqlValidator validator) {
        this.sourceConfig = sourceConfig;
        this.tableFilters = sourceConfig.getTableFilters();
        this.isBinlogSplitAssigned = isBinlogSplitAssigned;
        this.validator = validator;
    }

    @Override
    public void open() {
        jdbc = openMySqlConnection(sourceConfig.getDbzConfiguration());
    }

    @Override
    public Optional<MySqlSplit> getNext() {
        if (isBinlogSplitAssigned) {
            return Optional.empty();
        } else {
            isBinlogSplitAssigned = true;
            return Optional.of(createBinlogSplit());
        }
    }

    @Override
    public boolean waitingForFinishedSplits() {
        return false;
    }

    @Override
    public void onFinishedSplits(Map<String, BinlogOffset> splitFinishedOffsets) {
        // do nothing
    }

    @Override
    public void addSplits(Collection<MySqlSplit> splits) {
        // we don't store the split, but will re-create binlog split later
        isBinlogSplitAssigned = false;
    }

    @Override
    public PendingSplitsState snapshotState(long checkpointId) {
        return new BinlogPendingSplitsState(isBinlogSplitAssigned);
    }

    @Override
    public void notifyCheckpointComplete(long checkpointId) {
        // nothing to do
    }

    @Override
    public void close() {
        if (jdbc != null) {
            closeMySqlConnection(jdbc);
        }
    }

    // ------------------------------------------------------------------------------------------

    private MySqlBinlogSplit createBinlogSplit() {
        MySqlSchema mySqlSchema = new MySqlSchema(sourceConfig, jdbc);
        final List<TableId> capturedTableIds =
                discoverCapturedTables(jdbc, tableFilters, sourceConfig);
        mySqlSchema = validator.getResolvedMysqlSchema(mySqlSchema, capturedTableIds);
        // TODO: binlog-only source shouldn't need split key (e.g. no primary key tables),
        //  mock a split key here which should never be used later. We should refactor
        //  MySqlBinlogSplit ASAP.
        final RowType splitKeyType =
                (RowType) ROW(FIELD("id", DataTypes.BIGINT().notNull())).getLogicalType();
        return new MySqlBinlogSplit(
                BINLOG_SPLIT_ID,
                splitKeyType,
                currentBinlogOffset(jdbc),
                BinlogOffset.NO_STOPPING_OFFSET,
                Collections.emptyList(),
                mySqlSchema.getSchemas());
    }
}
