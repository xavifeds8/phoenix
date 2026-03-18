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
package org.apache.phoenix.compile;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.phoenix.compile.ExplainPlanAttributes.ExplainPlanAttributesBuilder;
import org.apache.phoenix.compile.GroupByCompiler.GroupBy;
import org.apache.phoenix.compile.OrderByCompiler.OrderBy;
import org.apache.phoenix.execute.visitor.QueryPlanVisitor;
import org.apache.phoenix.expression.Determinism;
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.expression.LiteralExpression;
import org.apache.phoenix.expression.RowKeyColumnExpression;
import org.apache.phoenix.iterate.DefaultParallelScanGrouper;
import org.apache.phoenix.iterate.ParallelScanGrouper;
import org.apache.phoenix.iterate.ResultIterator;
import org.apache.phoenix.jdbc.PhoenixStatement;
import org.apache.phoenix.jdbc.PhoenixStatement.Operation;
import org.apache.phoenix.metrics.MetricInfo;
import org.apache.phoenix.optimize.Cost;
import org.apache.phoenix.parse.FilterableStatement;
import org.apache.phoenix.parse.LiteralParseNode;
import org.apache.phoenix.parse.ParseNodeFactory;
import org.apache.phoenix.parse.TraceStatement;
import org.apache.phoenix.query.KeyRange;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PColumnImpl;
import org.apache.phoenix.schema.PName;
import org.apache.phoenix.schema.PNameFactory;
import org.apache.phoenix.schema.RowKeyValueAccessor;
import org.apache.phoenix.schema.SortOrder;
import org.apache.phoenix.schema.TableRef;
import org.apache.phoenix.schema.tuple.ResultTuple;
import org.apache.phoenix.schema.tuple.Tuple;
import org.apache.phoenix.schema.types.PLong;
import org.apache.phoenix.util.ByteUtil;
import org.apache.phoenix.util.EnvironmentEdgeManager;
import org.apache.phoenix.util.PhoenixKeyValueUtil;
import org.apache.phoenix.util.SizedUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query plan for the {@code TRACE ON} / {@code TRACE OFF} SQL commands.
 * <p>
 * <b>Deprecated:</b> The TRACE ON/OFF SQL mechanism is a legacy anti-pattern from the HTrace era.
 * With OpenTelemetry, tracing is always-on and controlled by sampling at the infrastructure level
 * (via {@code OTEL_TRACES_SAMPLER}), not per-connection via SQL commands. Users should use the
 * OpenTelemetry Java Agent for automatic tracing instead.
 * </p>
 * <p>
 * For backward compatibility, {@code TRACE ON} is now a no-op that returns the current trace ID if
 * an active OTel span exists (e.g., from the Java Agent), or 0 if no span is active.
 * {@code TRACE OFF} is also a no-op that returns 0. No spans are created or stored on the
 * connection.
 * </p>
 * @deprecated Use the OpenTelemetry Java Agent for automatic tracing. TRACE ON/OFF are no-ops.
 */
@Deprecated
public class TraceQueryPlan implements QueryPlan {

  private static final Logger LOG = LoggerFactory.getLogger(TraceQueryPlan.class);

  /**
   * Log the deprecation warning at most once per JVM to avoid log spam.
   */
  private static volatile boolean deprecationWarningLogged = false;

  private TraceStatement traceStatement = null;
  private PhoenixStatement stmt = null;
  private StatementContext context = null;
  private boolean first = true;

  private static final RowProjector TRACE_PROJECTOR;
  static {
    List<ExpressionProjector> projectedColumns = new ArrayList<ExpressionProjector>();
    PName colName = PNameFactory.newName(MetricInfo.TRACE.columnName);
    PColumn column = new PColumnImpl(PNameFactory.newName(MetricInfo.TRACE.columnName), null,
      PLong.INSTANCE, null, null, false, 0, SortOrder.getDefault(), 0, null, false, null, false,
      false, colName.getBytes(), HConstants.LATEST_TIMESTAMP);
    List<PColumn> columns = new ArrayList<PColumn>();
    columns.add(column);
    Expression expression = new RowKeyColumnExpression(column, new RowKeyValueAccessor(columns, 0));
    projectedColumns.add(new ExpressionProjector(MetricInfo.TRACE.columnName,
      MetricInfo.TRACE.columnName, "", expression, true));
    int estimatedByteSize = SizedUtil.KEY_VALUE_SIZE + PLong.INSTANCE.getByteSize();
    TRACE_PROJECTOR = new RowProjector(projectedColumns, estimatedByteSize, false);
  }

  public TraceQueryPlan(TraceStatement traceStatement, PhoenixStatement stmt) {
    this.traceStatement = traceStatement;
    this.stmt = stmt;
    this.context = new StatementContext(stmt);
  }

  @Override
  public Operation getOperation() {
    return traceStatement.getOperation();
  }

  @Override
  public StatementContext getContext() {
    return this.context;
  }

  @Override
  public ParameterMetaData getParameterMetaData() {
    return context.getBindManager().getParameterMetaData();
  }

  @Override
  public ResultIterator iterator() throws SQLException {
    return iterator(DefaultParallelScanGrouper.getInstance());
  }

  @Override
  public ResultIterator iterator(ParallelScanGrouper scanGrouper, Scan scan) throws SQLException {
    return iterator(scanGrouper);
  }

  @Override
  public ResultIterator iterator(ParallelScanGrouper scanGrouper) throws SQLException {
    logDeprecationWarning();
    return new TraceQueryResultIterator();
  }

  @Override
  public long getEstimatedSize() {
    return PLong.INSTANCE.getByteSize();
  }

  @Override
  public Cost getCost() {
    return Cost.ZERO;
  }

  @Override
  public Set<TableRef> getSourceRefs() {
    return Collections.emptySet();
  }

  @Override
  public TableRef getTableRef() {
    return null;
  }

  @Override
  public RowProjector getProjector() {
    return TRACE_PROJECTOR;
  }

  @Override
  public Integer getLimit() {
    return null;
  }

  @Override
  public Integer getOffset() {
    return null;
  }

  @Override
  public OrderBy getOrderBy() {
    return OrderBy.EMPTY_ORDER_BY;
  }

  @Override
  public GroupBy getGroupBy() {
    return GroupBy.EMPTY_GROUP_BY;
  }

  @Override
  public List<KeyRange> getSplits() {
    return Collections.emptyList();
  }

  @Override
  public List<List<Scan>> getScans() {
    return Collections.emptyList();
  }

  @Override
  public FilterableStatement getStatement() {
    return null;
  }

  @Override
  public boolean isDegenerate() {
    return false;
  }

  @Override
  public boolean isRowKeyOrdered() {
    return false;
  }

  @Override
  public ExplainPlan getExplainPlan() throws SQLException {
    return ExplainPlan.EMPTY_PLAN;
  }

  @Override
  public boolean useRoundRobinIterator() {
    return false;
  }

  @Override
  public <T> T accept(QueryPlanVisitor<T> visitor) {
    return visitor.visit(this);
  }

  @Override
  public Long getEstimatedRowsToScan() {
    return 0l;
  }

  @Override
  public Long getEstimatedBytesToScan() {
    return 0l;
  }

  @Override
  public Long getEstimateInfoTimestamp() throws SQLException {
    return 0l;
  }

  @Override
  public List<OrderBy> getOutputOrderBys() {
    return Collections.<OrderBy> emptyList();
  }

  @Override
  public boolean isApplicable() {
    return true;
  }

  private static void logDeprecationWarning() {
    if (!deprecationWarningLogged) {
      deprecationWarningLogged = true;
      LOG.warn("TRACE ON/OFF SQL commands are deprecated and are "
        + "now no-ops. Tracing is automatically handled by the "
        + "OpenTelemetry Java Agent. Configure sampling via "
        + "OTEL_TRACES_SAMPLER environment variable. "
        + "See https://phoenix.apache.org/tracing.html " + "for details.");
    }
  }

  /**
   * Result iterator that returns the current OTel trace ID (if any active span exists) without
   * creating or managing any spans. This is a backward-compatible no-op.
   */
  private class TraceQueryResultIterator implements ResultIterator {

    @Override
    public void close() throws SQLException {
    }

    @Override
    public Tuple next() throws SQLException {
      if (!first) {
        return null;
      }
      first = false;

      // Read the current span from OTel context (e.g., set by
      // the Java Agent). We never create or store spans — just
      // observe what's already there.
      Span currentSpan = Span.current();
      SpanContext spanContext = currentSpan.getSpanContext();

      long traceIdLong = 0L;
      if (spanContext.isValid()) {
        traceIdLong = parseTraceIdAsLong(spanContext.getTraceId());
        if (traceStatement.isTraceOn()) {
          LOG.info("TRACE ON (no-op): active OTel trace ID = {}", spanContext.getTraceId());
        } else {
          LOG.info("TRACE OFF (no-op): active OTel trace ID = {}", spanContext.getTraceId());
        }
      }

      // Return the trace ID to the client for backward compat.
      // Returns 0 if no active span exists.
      ImmutableBytesWritable ptr = new ImmutableBytesWritable();
      ParseNodeFactory factory = new ParseNodeFactory();
      LiteralParseNode literal = factory.literal(traceIdLong);
      LiteralExpression expression =
        LiteralExpression.newConstant(literal.getValue(), PLong.INSTANCE, Determinism.ALWAYS);
      expression.evaluate(null, ptr);
      byte[] rowKey = ByteUtil.copyKeyBytesIfNecessary(ptr);
      Cell cell = PhoenixKeyValueUtil.newKeyValue(rowKey, HConstants.EMPTY_BYTE_ARRAY,
        HConstants.EMPTY_BYTE_ARRAY, EnvironmentEdgeManager.currentTimeMillis(),
        HConstants.EMPTY_BYTE_ARRAY);
      List<Cell> cells = new ArrayList<Cell>(1);
      cells.add(cell);
      return new ResultTuple(Result.create(cells));
    }

    /**
     * Parse the first 16 hex characters of an OTel trace ID as a long. OTel trace IDs are
     * 32-character hex strings (128 bits). We take the lower 64 bits for backward compatibility
     * with the old HTrace long trace IDs.
     */
    private long parseTraceIdAsLong(String traceId) {
      if (traceId == null || traceId.length() < 16) {
        return 0L;
      }
      // Take the last 16 hex chars (lower 64 bits)
      String lower64 = traceId.substring(traceId.length() - 16);
      return Long.parseUnsignedLong(lower64, 16);
    }

    @Override
    public void explain(List<String> planSteps) {
    }

    @Override
    public void explain(List<String> planSteps,
      ExplainPlanAttributesBuilder explainPlanAttributesBuilder) {
    }
  }
}
