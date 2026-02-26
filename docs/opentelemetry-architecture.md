# Phoenix HTrace to OpenTelemetry Migration
## Architecture Design Document

**Status:** Design Phase
**Date:** February 2026
**Approach:** Follow HBase's proven pattern (HBASE-22120)

---

## 1. Executive Summary

This document describes the architecture for replacing the deprecated HTrace tracing system
in Apache Phoenix with OpenTelemetry. The design follows the exact same pattern that Apache
HBase successfully implemented in HBASE-22120, which is proven in production.

**Current State:** All HTrace imports have been replaced with 13 no-op stub classes in
`org.apache.phoenix.trace.stub.*`. Tracing is completely non-functional.

**Target State:** Full distributed tracing using OpenTelemetry API, with the SDK provided
at runtime by the operator via the Java Agent (same as HBase).

---

## 2. How HBase Does It (Verified from Source Code)

We verified HBase's actual implementation at `/Users/xfernand/code/os/hbase`:

### HBase's Three-Layer Architecture

```
LAYER 1: Code (compile-time)
  - opentelemetry-api only (NO SDK bundled)
  - opentelemetry-semconv for standard attributes
  - TraceUtil.java facade class (used by 71 files)
  - Calls GlobalOpenTelemetry.getTracer("org.apache.hbase")

LAYER 2: Java Agent (shipped in lib/trace/)
  - opentelemetry-javaagent.jar bundled in HBase distribution
  - Provides the SDK implementation at runtime
  - Auto-instruments Netty, JDBC, gRPC, thread pools
  - Controlled by HBASE_OTEL_TRACING_ENABLED=true

LAYER 3: Backend (operator's choice)
  - Configured via OTEL_TRACES_EXPORTER env var
  - Jaeger, Grafana Tempo, Zipkin, Datadog, etc.
```

### HBase's TraceUtil.java (actual code)

```java
public final class TraceUtil {

  public static Tracer getGlobalTracer() {
    return GlobalOpenTelemetry.getTracer("org.apache.hbase", VersionInfo.getVersion());
  }

  public static Span createSpan(String name) {
    return getGlobalTracer().spanBuilder(name)
      .setSpanKind(SpanKind.INTERNAL).startSpan();
  }

  public static Span createClientSpan(String name) {
    return getGlobalTracer().spanBuilder(name)
      .setSpanKind(SpanKind.CLIENT).startSpan();
  }

  public static Span createRemoteSpan(String name, Context ctx) {
    return getGlobalTracer().spanBuilder(name)
      .setParent(ctx).setSpanKind(SpanKind.SERVER).startSpan();
  }

  public static <T extends Throwable> void trace(
      ThrowingRunnable<T> runnable, String spanName) throws T {
    Span span = createSpan(spanName);
    try (Scope ignored = span.makeCurrent()) {
      runnable.run();
      span.setStatus(StatusCode.OK);
    } catch (Throwable e) {
      setError(span, e);
      throw e;
    } finally {
      span.end();
    }
  }
}
```

### HBase's Maven Dependencies

```xml
<!-- Root pom.xml -->
<opentelemetry.version>1.49.0</opentelemetry.version>
<opentelemetry-semconv.version>1.29.0-alpha</opentelemetry-semconv.version>
<opentelemetry-javaagent.version>2.15.0</opentelemetry-javaagent.version>

<!-- hbase-common/pom.xml (where TraceUtil lives) -->
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-api</artifactId>       <!-- API only! -->
</dependency>
<dependency>
  <groupId>io.opentelemetry.semconv</groupId>
  <artifactId>opentelemetry-semconv</artifactId>
</dependency>
```

### HBase's Runtime Configuration (hbase-env.sh)

```bash
# Enable the Java Agent
export HBASE_OTEL_TRACING_ENABLED=true

# Configure exporter
export OTEL_TRACES_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4317
export OTEL_METRICS_EXPORTER=none
export OTEL_LOGS_EXPORTER=none
export OTEL_SERVICE_NAME=hbase
```


---

## 3. Current Phoenix Architecture (Broken/Stubbed)

```
+-----------------------------------------------------------------------+
|                     PHOENIX CLIENT JVM                                 |
|                                                                        |
|  +------------------+  +------------------+  +------------------+     |
|  | PhoenixConnection|  | PhoenixStatement |  | MutationState    |     |
|  +--------+---------+  +--------+---------+  +--------+---------+     |
|           |                      |                      |              |
|           +----------------------+----------------------+              |
|                                  |                                     |
|                                  v                                     |
|                   +------------------------------+                     |
|                   |   Tracing.java (Facade)      |                     |
|                   |   startNewSpan()             |                     |
|                   |   isTracing()                |                     |
|                   |   wrap(callable)             |                     |
|                   +-------------+----------------+                     |
|                                 |                                      |
|                                 v                                      |
|                   +------------------------------+                     |
|                   |   HTrace Stub Package        |                     |
|                   |   (13 no-op classes)          |                     |
|                   |   Span, Trace, TraceScope,   |                     |
|                   |   Sampler, SpanReceiver,     |                     |
|                   |   Tracer, MilliSpan, etc.    |                     |
|                   +------------------------------+                     |
|                                 |                                      |
|                                 v                                      |
|                          [ALL NO-OPS]                                  |
|                     Tracing is DISABLED                                |
+-----------------------------------------------------------------------+

+-----------------------------------------------------------------------+
|                     PHOENIX SERVER (RegionServer)                       |
|                                                                        |
|  +------------------+  +------------------+  +------------------+     |
|  | IndexRegion      |  | BaseScannerRegion|  | MetaDataEndpoint |     |
|  | Observer         |  | Observer         |  | Impl             |     |
|  +--------+---------+  +--------+---------+  +--------+---------+     |
|           |                      |                      |              |
|           +----------------------+----------------------+              |
|                                  |                                     |
|                                  v                                     |
|                          [ALL NO-OPS]                                  |
|                     Tracing is DISABLED                                |
+-----------------------------------------------------------------------+

Files using stubs:
  Client-side: 16 files (35 imports)
  Server-side:  7 files (18 imports)
  Total:       23 files (53 imports)
```

### What Gets Deleted

| Category | Files | Count |
|----------|-------|-------|
| Stub classes | `trace/stub/Span.java`, `Trace.java`, `TraceScope.java`, `Sampler.java`, `SpanReceiver.java`, `Tracer.java`, `HTraceConfiguration.java`, `ProbabilitySampler.java`, `MilliSpan.java`, `TraceCallable.java`, `TraceRunnable.java`, `TimelineAnnotation.java`, `package-info.java` | 13 |
| Old trace infra | `Tracing.java`, `NullSpan.java`, `ConfigurationAdapter.java`, `TraceWriter.java`, `TraceSpanReceiver.java` | 5 |
| Old trace webapp | `phoenix-tracing-webapp/` (entire module) | 4+ |
| **Total deletions** | | **22+** |


---

## 4. Proposed Phoenix Architecture (Following HBase Pattern)

### High-Level Overview

```
+=========================================================================+
|                                                                          |
|   COMPILE TIME                          RUNTIME                          |
|   (what we code)                        (what operator configures)       |
|                                                                          |
|   +---------------------------+         +---------------------------+   |
|   | opentelemetry-api         |         | OpenTelemetry Java Agent  |   |
|   | opentelemetry-semconv     |         | (shipped by HBase in      |   |
|   |                           |         |  lib/trace/)              |   |
|   | Phoenix code calls:      |         |                           |   |
|   | GlobalOpenTelemetry       |  --->   | Provides SDK impl at      |   |
|   |   .getTracer("phoenix")  |         | runtime. If absent,       |   |
|   |                           |         | all calls are no-ops.     |   |
|   +---------------------------+         +---------------------------+   |
|                                                    |                     |
|                                                    | OTLP/gRPC          |
|                                                    v                     |
|                                         +---------------------------+   |
|                                         | Jaeger / Tempo / Zipkin   |   |
|                                         | (operator's choice)       |   |
|                                         +---------------------------+   |
|                                                                          |
+=========================================================================+
```

### Detailed Architecture Diagram

```
+=========================================================================+
|                        PHOENIX CLIENT JVM                                |
+=========================================================================+
|                                                                          |
|  +------------------+  +------------------+  +------------------+       |
|  | PhoenixConnection|  | PhoenixStatement |  | MutationState    |       |
|  | BaseQueryPlan    |  | TraceQueryPlan   |  | ParallelIterators|       |
|  | SerialIterators  |  | TracingIterator  |  |                  |       |
|  +--------+---------+  +--------+---------+  +--------+---------+       |
|           |                      |                      |                |
|           +----------------------+----------------------+                |
|                                  |                                       |
|                                  v                                       |
|           +----------------------------------------------+              |
|           |         PhoenixTracing.java  [NEW]           |              |
|           |         (Thin facade - like HBase TraceUtil) |              |
|           |                                              |              |
|           |  getTracer()                                 |              |
|           |    -> GlobalOpenTelemetry.getTracer(          |              |
|           |         "org.apache.phoenix", version)        |              |
|           |                                              |              |
|           |  createSpan(name)                            |              |
|           |    -> tracer.spanBuilder(name)               |              |
|           |         .setSpanKind(INTERNAL)               |              |
|           |         .startSpan()                         |              |
|           |                                              |              |
|           |  createClientSpan(name)                      |              |
|           |    -> tracer.spanBuilder(name)               |              |
|           |         .setSpanKind(CLIENT)                 |              |
|           |         .startSpan()                         |              |
|           |                                              |              |
|           |  trace(runnable, spanName)                   |              |
|           |    -> creates span, runs code, handles error |              |
|           |                                              |              |
|           |  tracedFuture(supplier, spanName)            |              |
|           |    -> traces async CompletableFuture         |              |
|           +----------------------+-----------------------+              |
|                                  |                                       |
|                                  v                                       |
|           +----------------------------------------------+              |
|           |         OpenTelemetry API                    |              |
|           |         (compile dependency only)            |              |
|           |                                              |              |
|           |  io.opentelemetry.api.trace.Tracer           |              |
|           |  io.opentelemetry.api.trace.Span             |              |
|           |  io.opentelemetry.api.trace.SpanKind         |              |
|           |  io.opentelemetry.api.trace.StatusCode       |              |
|           |  io.opentelemetry.context.Context            |              |
|           |  io.opentelemetry.context.Scope              |              |
|           +----------------------+-----------------------+              |
|                                  |                                       |
|                                  | If no SDK present: all no-ops        |
|                                  | If SDK present: real tracing         |
|                                  |                                       |
+==============================================+==========================+
                                               |
                                               | (SDK provided by Java Agent)
                                               |
+==============================================+==========================+
|                   JAVA AGENT (runtime)                                   |
|                                                                          |
|  +-------------------------------------------------------------------+ |
|  |  opentelemetry-javaagent.jar                                       | |
|  |  (Already shipped by HBase in lib/trace/)                          | |
|  |                                                                     | |
|  |  Provides:                                                          | |
|  |  +------------------+  +------------------+  +------------------+  | |
|  |  | SdkTracerProvider|  | BatchSpanProc.   |  | OtlpGrpcExporter|  | |
|  |  | (creates real    |  | (batches spans   |  | (sends to OTLP  |  | |
|  |  |  spans)          |  |  efficiently)    |  |  backend)       |  | |
|  |  +------------------+  +------------------+  +------------------+  | |
|  |                                                                     | |
|  |  Auto-instruments:                                                  | |
|  |  +------------------+  +------------------+  +------------------+  | |
|  |  | JDBC driver      |  | HBase RPC (Netty)|  | Thread pools    |  | |
|  |  | (auto-traces SQL)|  | (auto-traces RPC)|  | (context prop.) |  | |
|  |  +------------------+  +------------------+  +------------------+  | |
|  +-------------------------------------------------------------------+ |
|                                  |                                       |
+==============================================+==========================+
                                               |
                                               | OTLP/gRPC (port 4317)
                                               |
+==============================================+==========================+
|                   OBSERVABILITY BACKEND                                  |
|                                                                          |
|  +------------+  +------------+  +------------+  +------------+        |
|  |   Jaeger   |  |  Grafana   |  |   Zipkin   |  |  Datadog   |        |
|  |   (OSS)    |  |   Tempo    |  |   (OSS)    |  |  (SaaS)    |        |
|  +------------+  +------------+  +------------+  +------------+        |
|                                                                          |
+==========================================================================+
```

### Phoenix Server Layer

```
+=========================================================================+
|                   PHOENIX SERVER (inside HBase RegionServer JVM)          |
+=========================================================================+
|                                                                          |
|  +--------------------+  +--------------------+  +------------------+   |
|  | IndexRegionObserver|  | PhoenixTransactional|  | BaseScannerRegion|  |
|  |                    |  | Indexer             |  | Observer         |  |
|  +--------+-----------+  +--------+-----------+  +--------+---------+  |
|           |                       |                        |             |
|           +-----------------------+------------------------+             |
|                                   |                                      |
|                                   v                                      |
|            +----------------------------------------------+             |
|            |         PhoenixTracing.java                   |             |
|            |         (same facade, API-only)               |             |
|            +----------------------+-----------------------+             |
|                                   |                                      |
|                                   v                                      |
|            +----------------------------------------------+             |
|            |         OpenTelemetry API                     |             |
|            |         (no-op if agent not enabled)          |             |
|            +----------------------+-----------------------+             |
|                                   |                                      |
|                                   | Spans flow into HBase's             |
|                                   | OTel pipeline (same agent)          |
|                                   v                                      |
|            +----------------------------------------------+             |
|            |  HBase's Java Agent (already running)         |             |
|            |  Phoenix spans + HBase spans = unified trace  |             |
|            +----------------------------------------------+             |
|                                                                          |
+==========================================================================+
```


---

## 5. Libraries We Will Use

### Compile-Time Dependencies (added to pom.xml)

| Library | Artifact ID | Purpose | Where |
|---------|-------------|---------|-------|
| **OTel BOM** | `opentelemetry-bom` | Version alignment for all OTel jars | Root `pom.xml` dependencyManagement |
| **OTel API** | `opentelemetry-api` | Core interfaces: `Tracer`, `Span`, `Context`, `Scope` | `phoenix-core-client/pom.xml`, `phoenix-core-server/pom.xml` |
| **OTel Semconv** | `opentelemetry-semconv` | Standard attribute names: `db.system`, `db.operation` | `phoenix-core-client/pom.xml` |

### Runtime Dependencies (NOT in pom.xml - provided by Java Agent)

| Library | Artifact ID | Purpose | How Provided |
|---------|-------------|---------|--------------|
| **OTel SDK** | `opentelemetry-sdk` | SDK implementation | Java Agent bundles it |
| **OTel SDK Trace** | `opentelemetry-sdk-trace` | Trace-specific SDK | Java Agent bundles it |
| **OTel OTLP Exporter** | `opentelemetry-exporter-otlp` | OTLP/gRPC export | Java Agent bundles it |
| **OTel Java Agent** | `opentelemetry-javaagent` | Auto-instrumentation + SDK | HBase ships in `lib/trace/` |

### Test Dependencies

| Library | Artifact ID | Purpose | Where |
|---------|-------------|---------|-------|
| **OTel SDK Testing** | `opentelemetry-sdk-testing` | `InMemorySpanExporter` for tests | `phoenix-core/pom.xml` (test scope) |
| **OTel SDK** | `opentelemetry-sdk` | Needed to configure test SDK | `phoenix-core/pom.xml` (test scope) |

### Why API-Only at Compile Time?

```
Phoenix runs inside HBase's JVM (as coprocessors).
HBase already ships the Java Agent in lib/trace/.
If we bundle the SDK, we'd have TWO SDKs in the same JVM = conflict.

By depending on API-only:
  - If agent is present  -> real tracing (agent provides SDK)
  - If agent is absent   -> all calls are no-ops (zero overhead)
  - No version conflicts -> Phoenix uses whatever SDK HBase provides
```

---

## 6. New Files to Create

### Only 1 new file needed!

| File | Location | Purpose |
|------|----------|---------|
| `PhoenixTracing.java` | `phoenix-core-client/src/main/java/org/apache/phoenix/trace/PhoenixTracing.java` | Thin facade over OTel API. Equivalent to HBase's `TraceUtil.java`. |

### PhoenixTracing.java Design

```java
package org.apache.phoenix.trace;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Central tracing facade for Phoenix. Follows the same pattern as
 * HBase's TraceUtil (HBASE-22120).
 *
 * All methods are safe to call even when no OpenTelemetry SDK is
 * configured - they will be no-ops with zero overhead.
 */
public final class PhoenixTracing {

  private PhoenixTracing() {}

  public static Tracer getTracer() {
    return GlobalOpenTelemetry.getTracer("org.apache.phoenix");
  }

  /** Create an INTERNAL span (default for most operations). */
  public static Span createSpan(String name) {
    return getTracer().spanBuilder(name)
      .setSpanKind(SpanKind.INTERNAL).startSpan();
  }

  /** Create a CLIENT span (for outgoing RPC calls). */
  public static Span createClientSpan(String name) {
    return getTracer().spanBuilder(name)
      .setSpanKind(SpanKind.CLIENT).startSpan();
  }

  /** Create a SERVER span from remote context (for coprocessor calls). */
  public static Span createRemoteSpan(String name, Context ctx) {
    return getTracer().spanBuilder(name)
      .setParent(ctx).setSpanKind(SpanKind.SERVER).startSpan();
  }

  /** Check if current span is recording. */
  public static boolean isRecording() {
    return Span.current().isRecording();
  }

  /** Record an exception on the current span. */
  public static void setError(Span span, Throwable error) {
    span.recordException(error);
    span.setStatus(StatusCode.ERROR);
  }

  /** Trace a synchronous operation. */
  public static <T extends Throwable> void trace(
      ThrowingRunnable<T> runnable, String spanName) throws T {
    Span span = createSpan(spanName);
    try (Scope ignored = span.makeCurrent()) {
      runnable.run();
      span.setStatus(StatusCode.OK);
    } catch (Throwable e) {
      setError(span, e);
      throw e;
    } finally {
      span.end();
    }
  }

  /** Trace a synchronous operation that returns a value. */
  public static <R, T extends Throwable> R trace(
      ThrowingCallable<R, T> callable, String spanName) throws T {
    Span span = createSpan(spanName);
    try (Scope ignored = span.makeCurrent()) {
      R result = callable.call();
      span.setStatus(StatusCode.OK);
      return result;
    } catch (Throwable e) {
      setError(span, e);
      throw e;
    } finally {
      span.end();
    }
  }

  /** Wrap a Callable with the current context (for thread pools). */
  public static <V> Callable<V> wrap(Callable<V> callable) {
    return Context.current().wrap(callable);
  }

  /** Wrap a Runnable with the current context (for thread pools). */
  public static Runnable wrap(Runnable runnable) {
    return Context.current().wrap(runnable);
  }

  @FunctionalInterface
  public interface ThrowingRunnable<T extends Throwable> {
    void run() throws T;
  }

  @FunctionalInterface
  public interface ThrowingCallable<R, T extends Throwable> {
    R call() throws T;
  }
}
```

---

## 7. Trace Data Flow: How a Query Gets Traced

```
User runs: SELECT * FROM USERS WHERE id = 123

1. PhoenixStatement.executeQuery()
   |
   +-- PhoenixTracing.createSpan("phoenix.query.execute")
   |   span.setAttribute("db.system", "phoenix")
   |   span.setAttribute("db.operation", "SELECT")
   |   span.setAttribute("db.sql.table", "USERS")
   |
   +-- 2. Compile Phase
   |   |
   |   +-- PhoenixTracing.trace(() -> {
   |       compile(sql);
   |   }, "phoenix.query.compile")
   |
   +-- 3. Parallel Scan Phase
   |   |
   |   +-- PhoenixTracing.createSpan("phoenix.scan.parallel")
   |   |
   |   +-- Thread-1: (context auto-propagated via Context.current().wrap())
   |   |   +-- PhoenixTracing.createSpan("phoenix.scan.region")
   |   |       span.setAttribute("hbase.region", "USERS,\x00,123")
   |   |
   |   +-- Thread-2:
   |       +-- PhoenixTracing.createSpan("phoenix.scan.region")
   |           span.setAttribute("hbase.region", "USERS,\xFF,456")
   |
   +-- 4. Result Phase
       |
       +-- PhoenixTracing.createSpan("phoenix.result.iterate")
           span.setAttribute("rows.scanned", 1000)
           span.setAttribute("rows.returned", 50)

All spans automatically:
  - Batched by BatchSpanProcessor (agent)
  - Exported via OtlpGrpcExporter (agent)
  - Visible in Jaeger/Tempo UI
```

### What You See in Jaeger UI

```
phoenix.query.execute .......................... 75ms
  |-- phoenix.query.compile ................... 10ms
  |-- phoenix.scan.parallel ................... 50ms
  |     |-- phoenix.scan.region ............... 45ms
  |     |-- phoenix.scan.region ............... 42ms
  |-- phoenix.result.iterate .................. 15ms
```

### Unified Trace with HBase (when agent is enabled)

```
phoenix.query.execute .......................... 75ms
  |-- phoenix.query.compile ................... 10ms
  |-- phoenix.scan.parallel ................... 50ms
  |     |-- hbase.client.scan ................. 48ms    <-- HBase span!
  |     |     |-- hbase.region.scan ........... 45ms    <-- HBase span!
  |     |-- hbase.client.scan ................. 44ms    <-- HBase span!
  |     |     |-- hbase.region.scan ........... 42ms    <-- HBase span!
  |-- phoenix.result.iterate .................. 15ms
```


---

## 8. Configuration: How Users Enable Tracing

### For Operators (Production)

Since Phoenix runs inside HBase, tracing is enabled via HBase's existing mechanism:

```bash
# In hbase-env.sh (already exists in HBase!)
export HBASE_OTEL_TRACING_ENABLED=true
export OTEL_TRACES_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4317
export OTEL_SERVICE_NAME=hbase    # Phoenix spans tagged separately via tracer name
export OTEL_METRICS_EXPORTER=none
export OTEL_LOGS_EXPORTER=none

# Sampling (production: 1%, staging: 10%, dev: 100%)
export OTEL_TRACES_SAMPLER=traceidratio
export OTEL_TRACES_SAMPLER_ARG=0.01
```

### For Developers (Testing)

```java
// In integration tests, use InMemorySpanExporter
InMemorySpanExporter exporter = InMemorySpanExporter.create();
SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
    .build();
OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
    .setTracerProvider(tracerProvider)
    .buildAndRegisterGlobal();

// Run Phoenix operations...
connection.createStatement().executeQuery("SELECT * FROM test");

// Assert spans were created
List<SpanData> spans = exporter.getFinishedSpanItems();
assertThat(spans).isNotEmpty();
assertThat(spans.get(0).getName()).startsWith("phoenix.");
```

### For SQL Users (Session-level)

The `TRACE ON/OFF` SQL commands will be adapted to work with OTel:

```sql
TRACE ON;           -- Sets sampler to alwaysOn for this session
TRACE ON 0.5;       -- Sets sampler to 50% for this session
TRACE OFF;          -- Sets sampler to alwaysOff for this session
```

---

## 9. Migration Mapping: Old HTrace to New OTel

| Old (HTrace Stub) | New (OpenTelemetry) |
|--------------------|---------------------|
| `Tracing.startNewSpan(conn, desc)` | `PhoenixTracing.createSpan(desc)` |
| `Tracing.isTracing()` | `PhoenixTracing.isRecording()` |
| `Tracing.wrap(callable)` | `PhoenixTracing.wrap(callable)` |
| `Trace.currentSpan()` | `Span.current()` |
| `span.addKVAnnotation(k, v)` | `span.setAttribute(k, v)` |
| `span.addTimelineAnnotation(msg)` | `span.addEvent(msg)` |
| `scope.close()` | `scope.close()` + `span.end()` |
| `Sampler.ALWAYS` | `OTEL_TRACES_SAMPLER=always_on` |
| `Sampler.NEVER` | `OTEL_TRACES_SAMPLER=always_off` |
| `ProbabilitySampler(0.1)` | `OTEL_TRACES_SAMPLER=traceidratio` + `OTEL_TRACES_SAMPLER_ARG=0.1` |
| `TraceSpanReceiver` | Replaced by OTel `BatchSpanProcessor` (in agent) |
| `TraceWriter` | Replaced by OTel `OtlpGrpcExporter` (in agent) |
| `NullSpan` | Not needed (OTel returns no-op spans automatically) |
| `TraceCallable` / `TraceRunnable` | `Context.current().wrap(callable/runnable)` |
| `phoenix-tracing-webapp` | Replaced by Jaeger UI / Grafana Tempo UI |

---

## 10. Performance Characteristics

| Scenario | Overhead | Notes |
|----------|----------|-------|
| Agent NOT enabled | **Zero** | All OTel API calls return no-ops (~nanoseconds) |
| Agent enabled, 1% sampling | **~0.1%** | Only 1 in 100 requests create real spans |
| Agent enabled, 100% sampling | **~1-2%** | Every request traced; use only in dev/debug |
| Span creation (when sampled) | **~1-2 microseconds** | Negligible vs. query execution time |
| Span export | **Async, batched** | BatchSpanProcessor runs in background thread |
| Memory overhead (agent) | **~50-100 MB** | Agent + SDK + span buffer |

---

## 11. Files Modified During Migration

### Client-Side (phoenix-core-client) - 16 files

| File | Change |
|------|--------|
| `PhoenixConnection.java` | Replace `Sampler`/`TraceScope` with OTel `Span` |
| `PhoenixStatement.java` | Replace `Tracing.isTracing()` with `PhoenixTracing.isRecording()` |
| `MutationState.java` | Replace `Tracing.startNewSpan()` with `PhoenixTracing.createSpan()` |
| `BaseQueryPlan.java` | Replace `TraceScope` with OTel `Span` + `Scope` |
| `TraceQueryPlan.java` | Rewrite TRACE ON/OFF to configure OTel sampling |
| `ParallelIterators.java` | Replace `Tracing.wrap()` with `PhoenixTracing.wrap()` |
| `SerialIterators.java` | Same as ParallelIterators |
| `TracingIterator.java` | Replace `TraceScope` with OTel `Span` |
| `TracingUtils.java` | Simplify to use `span.setAttribute()` |
| `PhoenixMetricsSink.java` | Remove `Tracing.Frequency` reference |
| `QueryServicesOptions.java` | Remove `Tracing.Frequency` reference |
| `TraceReader.java` | Remove or rewrite (no more Phoenix trace table) |

### Server-Side (phoenix-core-server) - 7 files

| File | Change |
|------|--------|
| `IndexRegionObserver.java` | Replace `Trace`/`Span`/`NullSpan` with `PhoenixTracing` |
| `Indexer.java` | Same pattern |
| `PhoenixTransactionalIndexer.java` | Same pattern |
| `BaseScannerRegionObserver.java` | Replace `Trace.currentSpan()` with `Span.current()` |
| `LockManager.java` | Replace `Trace.startSpan()` with `PhoenixTracing.createSpan()` |
| `MetaDataEndpointImpl.java` | Replace `Tracing.addTraceMetricsSource()` with no-op (agent handles) |
| `UpdateStatisticsTool.java` | Remove `SpanReceiver` usage |

---

## 12. Summary: Why This Architecture

| Principle | How We Achieve It |
|-----------|-------------------|
| **Production-ready** | Follow HBase's proven pattern; API-only compile dep; zero overhead when off |
| **Efficient** | No SDK bundled; no Phoenix table writes; async batched export via agent |
| **User-friendly** | Operators use familiar `hbase-env.sh`; devs use `TRACE ON` SQL; rich Jaeger UI |
| **Minimal code** | 1 new file (`PhoenixTracing.java`); 22+ files deleted; 23 files modified |
| **No conflicts** | API-only dependency avoids SDK version conflicts with HBase |
| **Unified traces** | Phoenix spans + HBase spans in same trace (same agent, same backend) |
| **Industry standard** | OpenTelemetry is the CNCF standard; 90+ vendor support |

---

## 13. Comparison: Phoenix vs HBase Approach

| Aspect | HBase (HBASE-22120) | Phoenix (This Design) |
|--------|--------------------|-----------------------|
| Facade class | `TraceUtil.java` | `PhoenixTracing.java` |
| Tracer name | `"org.apache.hbase"` | `"org.apache.phoenix"` |
| Compile deps | `opentelemetry-api` + `semconv` | `opentelemetry-api` + `semconv` |
| SDK bundled? | No (agent provides) | No (agent provides) |
| Agent shipped? | Yes, in `lib/trace/` | No (uses HBase's agent) |
| Files instrumented | 71 | ~23 |
| Pattern | `TraceUtil.createSpan()` | `PhoenixTracing.createSpan()` |
| Async support | `tracedFuture()` | `tracedFuture()` (same pattern) |
| Context propagation | `Context.current().wrap()` | `Context.current().wrap()` |

**Phoenix follows HBase's pattern exactly.** The only difference is the tracer name
(`"org.apache.phoenix"` vs `"org.apache.hbase"`), which allows filtering Phoenix spans
separately in the observability backend.


---

## 14. Review of Migration Plan V2 Against HBase's Hybrid Approach

The existing "Migration Plan V2" has a solid structure but needs several adjustments
to align with HBase's proven hybrid approach. Here are the key issues and fixes:

### Issue 1: Phase 1 is Already Done

**V2 says:** "Create Stub Implementation (Week 1)" — create `NoOpTraceScope`, `TracingStub`

**Reality:** Phase 1 is already complete! The codebase already has 13 stub classes in
`org.apache.phoenix.trace.stub.*`. These are the existing stubs:
- `Span.java`, `Trace.java`, `TraceScope.java`, `Sampler.java`, `SpanReceiver.java`
- `Tracer.java`, `HTraceConfiguration.java`, `ProbabilitySampler.java`, `MilliSpan.java`
- `TraceCallable.java`, `TraceRunnable.java`, `TimelineAnnotation.java`, `package-info.java`

**Fix:** Skip Phase 1 entirely. Start from Phase 2 (removal).

### Issue 2: SDK Should NOT Be Bundled at Compile Time

**V2 says:** Add `opentelemetry-sdk`, `opentelemetry-sdk-trace`, `opentelemetry-exporter-otlp`
as compile dependencies in pom.xml.

**HBase's actual approach:** Only `opentelemetry-api` and `opentelemetry-semconv` at compile time.
The SDK is provided at runtime by the Java Agent.

**Why this matters:** Phoenix runs inside HBase's JVM (as coprocessors). If Phoenix bundles
the SDK and HBase also has the SDK (via its agent), you get version conflicts and
`GlobalOpenTelemetry` registration errors.

**Fix:** Remove SDK dependencies from pom.xml. Only keep:
```xml
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-api</artifactId>
</dependency>
<dependency>
  <groupId>io.opentelemetry.semconv</groupId>
  <artifactId>opentelemetry-semconv</artifactId>
</dependency>
```

### Issue 3: PhoenixTracing Should NOT Initialize the Tracer Eagerly

**V2 says:**
```java
private static final Tracer tracer = GlobalOpenTelemetry.getTracer(
    INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION);
```

**Problem:** This initializes at class-load time. If `GlobalOpenTelemetry` hasn't been
configured yet (common in tests), this will either fail or return a permanent no-op
that never gets replaced.

**HBase's approach:** Call `GlobalOpenTelemetry.getTracer()` on every call:
```java
public static Tracer getGlobalTracer() {
    return GlobalOpenTelemetry.getTracer("org.apache.hbase", VersionInfo.getVersion());
}
```

**Fix:** Don't cache the tracer in a static final field. Call `getTracer()` each time
(it's internally cached by the OTel SDK, so there's no performance penalty).

### Issue 4: PhoenixTracing.isEnabled() is Wrong

**V2 says:**
```java
public static boolean isEnabled() {
    try {
        return GlobalOpenTelemetry.get() != null;
    } catch (Exception e) {
        return false;
    }
}
```

**Problem:** `GlobalOpenTelemetry.get()` never returns null — it returns a no-op instance
if no SDK is configured. This method would always return `true`.

**HBase's approach:** Check if the current span is recording:
```java
public static boolean isRecording() {
    return Span.current().isRecording();
}
```

**Fix:** Use `Span.current().isRecording()` instead.

### Issue 5: Missing Async/Future Tracing Support

**V2 says:** Only provides `startSpan()` and `startClientSpan()`.

**HBase provides:** `tracedFuture()`, `tracedRunnable()`, `trace(ThrowingRunnable)`,
`trace(ThrowingCallable)` — all essential for Phoenix's parallel scan operations.

**Fix:** Add these methods to `PhoenixTracing.java` (see Section 6 of this document
for the complete design).

### Issue 6: PhoenixTraceScope Wraps Span+Scope Together

**V2 says:** `PhoenixTraceScope` wraps both `Span` and `Scope` and closes both in `close()`.

**HBase's approach:** Does NOT use a wrapper class. Uses raw `Span` + `Scope` directly:
```java
Span span = createSpan(name);
try (Scope ignored = span.makeCurrent()) {
    // work
} finally {
    span.end();
}
```

**Assessment:** The V2 `PhoenixTraceScope` wrapper is actually fine for Phoenix's use case.
It makes the API more user-friendly. HBase doesn't need it because their `trace()` method
handles the lifecycle. But for Phoenix, where callers manually manage spans, the wrapper
is a good convenience. **Keep it, but make it optional** — also provide the raw
`createSpan()` + `trace()` methods like HBase does.

### Issue 7: Jaeger is Not a Compile-Time Concern

**V2 says:** Phase 4 is "Add Jaeger Integration" with specific Jaeger configuration.

**Reality:** With the hybrid approach, Jaeger integration is purely a runtime/deployment
concern. Phoenix code never mentions Jaeger. The Java Agent handles all export.

**Fix:** Phase 4 should be "Documentation for Operators" — how to configure the Java Agent
to export to Jaeger/Tempo/etc. No code changes needed.

---

## 15. Revised Migration Plan (Aligned with HBase Hybrid Approach)

### Phase 1: SKIP (Already Done)
Stubs already exist in `org.apache.phoenix.trace.stub.*`.

### Phase 2: Add OTel API Dependency + Create PhoenixTracing (Week 1-2)

1. Add `opentelemetry-bom`, `opentelemetry-api`, `opentelemetry-semconv` to pom.xml
   - **API only, NO SDK**
2. Create `PhoenixTracing.java` (following HBase's `TraceUtil` pattern)
3. Add `opentelemetry-sdk-testing` as test-scope dependency
4. Verify: `mvn clean compile` succeeds

### Phase 3: Migrate All Callers (Week 3-4)

Replace stub usage with `PhoenixTracing` calls in all 23 files:

**Batch A — Client core (8 files):**
- PhoenixConnection, PhoenixStatement, MutationState, BaseQueryPlan
- TraceQueryPlan, ParallelIterators, SerialIterators, TracingIterator

**Batch B — Server side (7 files):**
- IndexRegionObserver, Indexer, PhoenixTransactionalIndexer
- BaseScannerRegionObserver, LockManager, MetaDataEndpointImpl
- UpdateStatisticsTool

**Batch C — Trace infrastructure (5 files):**
- TracingUtils, PhoenixMetricsSink, QueryServicesOptions
- TraceReader (remove or rewrite), ANTLR grammar (if needed)

Verify after each batch: `mvn clean compile`

### Phase 4: Delete Old Files (Week 5)

1. Delete entire `trace/stub/` package (13 files)
2. Delete `Tracing.java`, `NullSpan.java`, `ConfigurationAdapter.java`
3. Delete `TraceWriter.java`, `TraceSpanReceiver.java`, `TraceReader.java`
4. Consider removing `phoenix-tracing-webapp` module
5. Add HTrace import ban to maven-enforcer-plugin
6. Verify: `mvn clean install` succeeds

### Phase 5: Tests + Documentation (Week 6-7)

1. Rewrite tracing integration tests using `InMemorySpanExporter`
2. Create `docs/TRACING.md` with operator guide
3. Performance benchmarking
4. Verify: full test suite passes

### Phase 6: Open Source Contribution (Week 8)

1. Create JIRA ticket
2. Prepare PR following Apache guidelines
3. Community discussion on dev@phoenix.apache.org

### Key Difference from V2

```
V2 Plan:                          Revised Plan (HBase Hybrid):
                                  
Phase 1: Create stubs             Phase 1: SKIP (already done)
Phase 2: Remove HTrace            Phase 2: Add OTel API + PhoenixTracing
Phase 3: Add OTel (with SDK!)     Phase 3: Migrate callers (API only!)
Phase 4: Jaeger integration       Phase 4: Delete old files
Phase 5: Testing                  Phase 5: Tests + docs
Phase 6: Contribution             Phase 6: Contribution

SDK bundled: YES                  SDK bundled: NO (agent provides)
Jaeger in code: YES               Jaeger in code: NO (runtime config)
PhoenixTraceScope: Required       PhoenixTraceScope: Optional convenience
Tracer init: Eager (broken)       Tracer init: Per-call (correct)
```

