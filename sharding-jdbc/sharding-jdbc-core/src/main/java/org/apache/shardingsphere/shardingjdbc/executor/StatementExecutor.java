/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.shardingjdbc.executor;

import org.apache.shardingsphere.underlying.executor.StatementExecuteUnit;
import org.apache.shardingsphere.sharding.execute.sql.execute.SQLExecutorCallback;
import org.apache.shardingsphere.sharding.execute.sql.execute.result.MemoryQueryResult;
import org.apache.shardingsphere.sharding.execute.sql.execute.result.StreamQueryResult;
import org.apache.shardingsphere.sharding.execute.sql.execute.threadlocal.ExecutorExceptionHandler;
import org.apache.shardingsphere.underlying.executor.group.StatementExecuteGroupEngine;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.connection.ShardingConnection;
import org.apache.shardingsphere.underlying.common.config.properties.ConfigurationPropertyKey;
import org.apache.shardingsphere.underlying.executor.QueryResult;
import org.apache.shardingsphere.underlying.executor.connection.StatementOption;
import org.apache.shardingsphere.underlying.executor.constant.ConnectionMode;
import org.apache.shardingsphere.underlying.executor.context.ExecutionContext;
import org.apache.shardingsphere.underlying.executor.context.ExecutionUnit;
import org.apache.shardingsphere.underlying.executor.kernel.InputGroup;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;

/**
 * Statement executor.
 */
public final class StatementExecutor extends AbstractStatementExecutor {
    
    private StatementExecuteGroupEngine executeGroupEngine;
    
    public StatementExecutor(final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability, final ShardingConnection shardingConnection) {
        super(resultSetType, resultSetConcurrency, resultSetHoldability, shardingConnection);
        int maxConnectionsSizePerQuery = shardingConnection.getRuntimeContext().getProperties().<Integer>getValue(ConfigurationPropertyKey.MAX_CONNECTIONS_SIZE_PER_QUERY);
        executeGroupEngine = new StatementExecuteGroupEngine(maxConnectionsSizePerQuery);
    }
    
    /**
     * Initialize executor.
     *
     * @param executionContext execution context
     * @throws SQLException SQL exception
     */
    public void init(final ExecutionContext executionContext) throws SQLException {
        setSqlStatementContext(executionContext.getSqlStatementContext());
        getInputGroups().addAll(getExecuteGroups(executionContext.getExecutionUnits()));
        cacheStatements();
    }
    
    @SuppressWarnings("MagicConstant")
    private Collection<InputGroup<StatementExecuteUnit>> getExecuteGroups(final Collection<ExecutionUnit> executionUnits) throws SQLException {
        StatementOption statementOption = new StatementOption(getResultSetType(), getResultSetConcurrency(), getResultSetHoldability());
        return executeGroupEngine.generate(executionUnits, getConnection(), statementOption);
    }
    
    /**
     * Execute query.
     * 
     * @return result set list
     * @throws SQLException SQL exception
     */
    public List<QueryResult> executeQuery() throws SQLException {
        boolean isExceptionThrown = ExecutorExceptionHandler.isExceptionThrown();
        SQLExecutorCallback<QueryResult> executeCallback = new SQLExecutorCallback<QueryResult>(getDatabaseType(), isExceptionThrown) {
            
            @Override
            protected QueryResult executeSQL(final String sql, final Statement statement, final ConnectionMode connectionMode) throws SQLException {
                return getQueryResult(sql, statement, connectionMode);
            }
        };
        return executeCallback(executeCallback);
    }
    
    private QueryResult getQueryResult(final String sql, final Statement statement, final ConnectionMode connectionMode) throws SQLException {
        ResultSet resultSet = statement.executeQuery(sql);
        getResultSets().add(resultSet);
        return ConnectionMode.MEMORY_STRICTLY == connectionMode ? new StreamQueryResult(resultSet) : new MemoryQueryResult(resultSet);
    }
    
    /**
     * Execute update.
     * 
     * @return effected records count
     * @throws SQLException SQL exception
     */
    public int executeUpdate() throws SQLException {
        return executeUpdate(Statement::executeUpdate);
    }
    
    /**
     * Execute update with auto generated keys.
     * 
     * @param autoGeneratedKeys auto generated keys' flag
     * @return effected records count
     * @throws SQLException SQL exception
     */
    public int executeUpdate(final int autoGeneratedKeys) throws SQLException {
        return executeUpdate((statement, sql) -> statement.executeUpdate(sql, autoGeneratedKeys));
    }
    
    /**
     * Execute update with column indexes.
     *
     * @param columnIndexes column indexes
     * @return effected records count
     * @throws SQLException SQL exception
     */
    public int executeUpdate(final int[] columnIndexes) throws SQLException {
        return executeUpdate((statement, sql) -> statement.executeUpdate(sql, columnIndexes));
    }
    
    /**
     * Execute update with column names.
     *
     * @param columnNames column names
     * @return effected records count
     * @throws SQLException SQL exception
     */
    public int executeUpdate(final String[] columnNames) throws SQLException {
        return executeUpdate((statement, sql) -> statement.executeUpdate(sql, columnNames));
    }
    
    private int executeUpdate(final Updater updater) throws SQLException {
        final boolean isExceptionThrown = ExecutorExceptionHandler.isExceptionThrown();
        SQLExecutorCallback<Integer> executeCallback = new SQLExecutorCallback<Integer>(getDatabaseType(), isExceptionThrown) {
            
            @Override
            protected Integer executeSQL(final String sql, final Statement statement, final ConnectionMode connectionMode) throws SQLException {
                return updater.executeUpdate(statement, sql);
            }
        };
        List<Integer> results = executeCallback(executeCallback);
        if (isAccumulate()) {
            return accumulate(results);
        } else {
            return null == results.get(0) ? 0 : results.get(0);
        }
    }
    
    private int accumulate(final List<Integer> results) {
        int result = 0;
        for (Integer each : results) {
            result += null == each ? 0 : each;
        }
        return result;
    }
    
    /**
     * Execute SQL.
     *
     * @return return true if is DQL, false if is DML
     * @throws SQLException SQL exception
     */
    public boolean execute() throws SQLException {
        return execute(Statement::execute);
    }
    
    /**
     * Execute SQL with auto generated keys.
     *
     * @param autoGeneratedKeys auto generated keys' flag
     * @return return true if is DQL, false if is DML
     * @throws SQLException SQL exception
     */
    public boolean execute(final int autoGeneratedKeys) throws SQLException {
        return execute((statement, sql) -> statement.execute(sql, autoGeneratedKeys));
    }
    
    /**
     * Execute SQL with column indexes.
     *
     * @param columnIndexes column indexes
     * @return return true if is DQL, false if is DML
     * @throws SQLException SQL exception
     */
    public boolean execute(final int[] columnIndexes) throws SQLException {
        return execute((statement, sql) -> statement.execute(sql, columnIndexes));
    }
    
    /**
     * Execute SQL with column names.
     *
     * @param columnNames column names
     * @return return true if is DQL, false if is DML
     * @throws SQLException SQL exception
     */
    public boolean execute(final String[] columnNames) throws SQLException {
        return execute((statement, sql) -> statement.execute(sql, columnNames));
    }
    
    private boolean execute(final Executor executor) throws SQLException {
        final boolean isExceptionThrown = ExecutorExceptionHandler.isExceptionThrown();
        SQLExecutorCallback<Boolean> executeCallback = new SQLExecutorCallback<Boolean>(getDatabaseType(), isExceptionThrown) {
            
            @Override
            protected Boolean executeSQL(final String sql, final Statement statement, final ConnectionMode connectionMode) throws SQLException {
                return executor.execute(statement, sql);
            }
        };
        List<Boolean> result = executeCallback(executeCallback);
        if (null == result || result.isEmpty() || null == result.get(0)) {
            return false;
        }
        return result.get(0);
    }
    
    private interface Updater {
        
        int executeUpdate(Statement statement, String sql) throws SQLException;
    }
    
    private interface Executor {
        
        boolean execute(Statement statement, String sql) throws SQLException;
    }
}

