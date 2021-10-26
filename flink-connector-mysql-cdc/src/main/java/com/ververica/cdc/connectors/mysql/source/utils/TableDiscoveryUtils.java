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

package com.ververica.cdc.connectors.mysql.source.utils;

import org.apache.flink.util.FlinkRuntimeException;

import com.ververica.cdc.connectors.mysql.schema.MySqlSchema;
import com.ververica.cdc.connectors.mysql.source.config.MySqlSourceConfig;
import io.debezium.connector.mysql.MySqlConnection;
import io.debezium.relational.RelationalTableFilters;
import io.debezium.relational.TableId;
import io.debezium.relational.history.TableChanges.TableChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ververica.cdc.connectors.mysql.source.utils.StatementUtils.quote;

/** Utilities to discovery matched tables. */
public class TableDiscoveryUtils {
    private static final Logger LOG = LoggerFactory.getLogger(TableDiscoveryUtils.class);

    private static List<TableId> listTables(
            MySqlConnection jdbc, RelationalTableFilters tableFilters) throws SQLException {
        final List<TableId> capturedTableIds = new ArrayList<>();
        // -------------------
        // READ DATABASE NAMES
        // -------------------
        // Get the list of databases ...
        LOG.info("Read list of available databases");
        final List<String> databaseNames = new ArrayList<>();

        jdbc.query(
                "SHOW DATABASES",
                rs -> {
                    while (rs.next()) {
                        databaseNames.add(rs.getString(1));
                    }
                });
        LOG.info("\t list of available databases is: {}", databaseNames);

        // ----------------
        // READ TABLE NAMES
        // ----------------
        // Get the list of table IDs for each database. We can't use a prepared statement with
        // MySQL, so we have to build the SQL statement each time. Although in other cases this
        // might lead to SQL injection, in our case we are reading the database names from the
        // database and not taking them from the user ...
        LOG.info("Read list of available tables in each database");
        for (String dbName : databaseNames) {
            try {
                jdbc.query(
                        "SHOW FULL TABLES IN " + quote(dbName) + " where Table_Type = 'BASE TABLE'",
                        rs -> {
                            while (rs.next()) {
                                TableId tableId = new TableId(dbName, null, rs.getString(1));
                                if (tableFilters.dataCollectionFilter().isIncluded(tableId)) {
                                    capturedTableIds.add(tableId);
                                    LOG.info("\t including '{}' for further processing", tableId);
                                } else {
                                    LOG.info("\t '{}' is filtered out of capturing", tableId);
                                }
                            }
                        });
            } catch (SQLException e) {
                // We were unable to execute the query or process the results, so skip this ...
                LOG.warn(
                        "\t skipping database '{}' due to error reading tables: {}",
                        dbName,
                        e.getMessage());
            }
        }
        return capturedTableIds;
    }

    public static List<TableId> discoverCapturedTables(
            MySqlConnection jdbc,
            RelationalTableFilters tableFilters,
            MySqlSourceConfig sourceConfig) {
        final List<TableId> capturedTableIds;
        try {
            capturedTableIds = listTables(jdbc, tableFilters);
        } catch (SQLException e) {
            throw new FlinkRuntimeException("Failed to discover captured tables", e);
        }
        if (capturedTableIds.isEmpty()) {
            throw new IllegalArgumentException(
                    String.format(
                            "Can't find any matched tables, please check your configured database-name: %s and table-name: %s",
                            sourceConfig.getDatabaseList(), sourceConfig.getTableList()));
        }
        return capturedTableIds;
    }

    public static Map<TableId, TableChange> getTableSchemas(
            MySqlSchema mySqlSchema, List<TableId> tableIds) {
        Map<TableId, TableChange> tableSchemas = new HashMap<>();
        for (TableId tableId : tableIds) {
            TableChange tableSchema = mySqlSchema.getTableSchema(tableId);
            tableSchemas.put(tableId, tableSchema);
        }
        return tableSchemas;
    }

    public static Map<TableId, TableChange> discoverCapturedTableSchemas(
            MySqlConnection jdbc,
            RelationalTableFilters tableFilters,
            MySqlSourceConfig sourceConfig) {
        final List<TableId> capturedTableIds =
                discoverCapturedTables(jdbc, tableFilters, sourceConfig);
        MySqlSchema mySqlSchema = new MySqlSchema(sourceConfig, jdbc);
        return getTableSchemas(mySqlSchema, capturedTableIds);
    }
}
