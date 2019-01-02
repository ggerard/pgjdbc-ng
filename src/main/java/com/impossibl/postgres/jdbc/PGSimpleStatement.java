/**
 * Copyright (c) 2013, impossibl.com
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *  * Neither the name of impossibl.com nor the names of its contributors may
 *    be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.impossibl.postgres.jdbc;

import com.impossibl.postgres.protocol.ResultBatch;
import com.impossibl.postgres.protocol.ResultBatches;

import static com.impossibl.postgres.jdbc.Exceptions.INVALID_COMMAND_FOR_GENERATED_KEYS;
import static com.impossibl.postgres.jdbc.Exceptions.NOT_SUPPORTED;
import static com.impossibl.postgres.jdbc.Exceptions.NO_RESULT_COUNT_AVAILABLE;
import static com.impossibl.postgres.jdbc.Exceptions.NO_RESULT_SET_AVAILABLE;
import static com.impossibl.postgres.jdbc.SQLTextUtils.appendReturningClause;
import static com.impossibl.postgres.jdbc.SQLTextUtils.prependCursorDeclaration;

import java.sql.BatchUpdateException;
import java.sql.ResultSet;
import java.sql.SQLException;

import static java.lang.Long.max;
import static java.lang.Long.min;
import static java.util.Arrays.asList;


class PGSimpleStatement extends PGStatement {

  private SQLText batchCommands;

  PGSimpleStatement(PGDirectConnection connection, int type, int concurrency, int holdability) {
    super(connection, type, concurrency, holdability, null, null);
  }

  private void setup(SQLText sqlText) {

    if (sqlText.getStatementCount() > 1) {
      return;
    }

    if (resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {

      name = connection.getNextStatementName();

      cursorName = "cursor" + name;

      if (!prependCursorDeclaration(sqlText, cursorName, resultSetType, resultSetHoldability, connection.autoCommit)) {

        cursorName = name = null;
      }

    }

  }

  boolean execute(SQLText sqlText) throws SQLException {

    if (name != null) {

      dispose(connection, name);

      name = null;
    }

    if (processEscapes) {
      SQLTextEscapes.processEscapes(sqlText, connection);
    }

    setup(sqlText);

    boolean result = executeDirect(sqlText.toString());

    if (cursorName != null) {
      result = executeDirect("FETCH ABSOLUTE 0 FROM " + cursorName);
    }

    return result;
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    checkClosed();

    SQLText sqlText = connection.parseSQL(sql);

    return execute(sqlText);
  }

  @Override
  public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
    checkClosed();

    SQLText sqlText = connection.parseSQL(sql);

    if (autoGeneratedKeys != RETURN_GENERATED_KEYS) {
      return execute(sqlText);
    }

    if (!appendReturningClause(sqlText)) {
      throw INVALID_COMMAND_FOR_GENERATED_KEYS;
    }

    execute(sqlText);

    generatedKeysResultSet = getResultSet();

    return false;
  }

  @Override
  public boolean execute(String sql, int[] columnIndexes) throws SQLException {
    checkClosed();

    throw NOT_SUPPORTED;
  }

  @Override
  public boolean execute(String sql, String[] columnNames) throws SQLException {
    checkClosed();

    SQLText sqlText = connection.parseSQL(sql);

    if (!appendReturningClause(sqlText, asList(columnNames))) {
      throw INVALID_COMMAND_FOR_GENERATED_KEYS;
    }

    execute(sqlText);

    generatedKeysResultSet = getResultSet();

    return false;
  }

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {

    if (!execute(sql)) {
      throw NO_RESULT_SET_AVAILABLE;
    }

    return getResultSet();
  }

  @Override
  public long executeLargeUpdate(String sql) throws SQLException {

    if (execute(sql)) {
      throw NO_RESULT_COUNT_AVAILABLE;
    }

    return max(getLargeUpdateCount(), 0);
  }

  @Override
  public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException {

    if (execute(sql, autoGeneratedKeys)) {
      throw NO_RESULT_COUNT_AVAILABLE;
    }

    return max(getLargeUpdateCount(), 0);
  }

  @Override
  public long executeLargeUpdate(String sql, int[] columnIndexes) throws SQLException {

    if (execute(sql, columnIndexes)) {
      throw NO_RESULT_COUNT_AVAILABLE;
    }

    return max(getLargeUpdateCount(), 0);
  }

  @Override
  public long executeLargeUpdate(String sql, String[] columnNames) throws SQLException {

    if (execute(sql, columnNames)) {
      throw NO_RESULT_COUNT_AVAILABLE;
    }

    return max(getLargeUpdateCount(), 0);
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {

    long count = executeLargeUpdate(sql);
    return (int) min(count, Integer.MAX_VALUE);
  }

  @Override
  public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {

    long count = executeLargeUpdate(sql, autoGeneratedKeys);
    return (int) min(count, Integer.MAX_VALUE);
  }

  @Override
  public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {

    long count = executeLargeUpdate(sql, columnIndexes);
    return (int) min(count, Integer.MAX_VALUE);
  }

  @Override
  public int executeUpdate(String sql, String[] columnNames) throws SQLException {

    long count = executeLargeUpdate(sql, columnNames);
    return (int) min(count, Integer.MAX_VALUE);
  }

  @Override
  public void addBatch(String sql) throws SQLException {
    checkClosed();

    SQLText sqlText = connection.parseSQL(sql);

    if (batchCommands == null) {
      batchCommands = sqlText;
    }
    else {
      batchCommands.addStatements(sqlText);
    }
  }

  @Override
  public void clearBatch() {

    batchCommands = null;
  }

  @Override
  public int[] executeBatch() throws SQLException {
    checkClosed();

    IntegerBatchResults results = new IntegerBatchResults();
    executeBatch(results);
    return results.counts;
  }

  @Override
  public long[] executeLargeBatch() throws SQLException {
    checkClosed();

    LongBatchResults results = new LongBatchResults();
    executeBatch(results);
    return results.counts;
  }

  private void executeBatch(BatchResults results) throws SQLException {

    int batchIdx = 0;
    try {

      warningChain = null;

      if (batchCommands == null) {
        return;
      }

      execute(batchCommands);

      results.setBatchSize(resultBatches.size());

      for (batchIdx = 0; batchIdx < resultBatches.size(); ++batchIdx) {

        ResultBatch resultBatch = resultBatches.get(batchIdx);

        if (resultBatch.getCommand().equals("SELECT")) {
          throw results.getException(batchIdx, "SELECT in executeBatch", null);
        }
        else if (resultBatch.getRowsAffected() != null) {
          results.setUpdateCount(batchIdx, resultBatch.getRowsAffected());
        }
        else {
          results.setUpdateCount(batchIdx, SUCCESS_NO_INFO);
        }
      }

    }
    catch (BatchUpdateException bue) {
      throw bue;
    }
    catch (SQLException se) {
      throw results.getException(batchIdx, null, se);
    }
    finally {
      resultBatches = ResultBatches.releaseAll(resultBatches);
      batchCommands = null;
      query = null;
    }

  }

}
