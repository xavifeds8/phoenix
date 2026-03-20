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
package org.apache.phoenix.trace;

import io.opentelemetry.api.common.AttributeKey;

/**
 * Semantic attribute keys for Phoenix tracing spans, following the
 * <a href="https://opentelemetry.io/docs/specs/semconv/database/">OpenTelemetry Database Semantic
 * Conventions</a>.
 * <p>
 * Standard OTel {@code db.*} attributes are used for interoperability with generic observability
 * tooling (Jaeger, Grafana, Datadog, etc.). Phoenix-specific attributes use the {@code phoenix.*}
 * namespace for domain-specific details.
 * </p>
 * @see <a href="https://issues.apache.org/jira/browse/PHOENIX-5215">PHOENIX-5215</a>
 */
public final class PhoenixTracingAttributes {

  private PhoenixTracingAttributes() {
  }

  // -----------------------------------------------------------------------
  // OTel Database Semantic Convention attributes (db.*)
  // -----------------------------------------------------------------------

  /** Database system identifier. Always {@code "phoenix"} for Apache Phoenix. */
  public static final AttributeKey<String> DB_SYSTEM = AttributeKey.stringKey("db.system");

  /** The database statement being executed (e.g. the SQL text). */
  public static final AttributeKey<String> DB_STATEMENT = AttributeKey.stringKey("db.statement");

  /** The name of the operation being executed (e.g. {@code SELECT}, {@code UPSERT}). */
  public static final AttributeKey<String> DB_OPERATION = AttributeKey.stringKey("db.operation");

  /** The target database/table name. */
  public static final AttributeKey<String> DB_NAME = AttributeKey.stringKey("db.name");

  // -----------------------------------------------------------------------
  // Phoenix-specific attributes (phoenix.*)
  // -----------------------------------------------------------------------

  /** Phoenix schema name (empty string for default schema). */
  public static final AttributeKey<String> PHOENIX_SCHEMA =
    AttributeKey.stringKey("phoenix.schema");

  /** Whether auto-commit is enabled on the connection. */
  public static final AttributeKey<Boolean> PHOENIX_AUTOCOMMIT =
    AttributeKey.booleanKey("phoenix.autocommit");

  /** Number of rows in a mutation batch. */
  public static final AttributeKey<Long> PHOENIX_MUTATION_ROWS =
    AttributeKey.longKey("phoenix.mutation.rows");

  /** Size of a mutation batch in bytes. */
  public static final AttributeKey<Long> PHOENIX_MUTATION_BYTES =
    AttributeKey.longKey("phoenix.mutation.bytes");

  /** Number of tables involved in a mutation commit. */
  public static final AttributeKey<Long> PHOENIX_MUTATION_TABLES =
    AttributeKey.longKey("phoenix.mutation.tables");

  /**
   * Type of scan being performed. Values: {@code "POINT_LOOKUP"}, {@code "FULL"}, {@code "RANGE"}.
   */
  public static final AttributeKey<String> PHOENIX_SCAN_TYPE =
    AttributeKey.stringKey("phoenix.scan.type");

  /** Index table being maintained or scanned. */
  public static final AttributeKey<String> PHOENIX_INDEX_TABLE =
    AttributeKey.stringKey("phoenix.index.table");

  /** Number of index updates generated for a batch of mutations. */
  public static final AttributeKey<Long> PHOENIX_INDEX_UPDATE_COUNT =
    AttributeKey.longKey("phoenix.index.update.count");

  // -----------------------------------------------------------------------
  // Constant values
  // -----------------------------------------------------------------------

  /** The value for {@link #DB_SYSTEM}. */
  public static final String DB_SYSTEM_VALUE = "phoenix";
}
