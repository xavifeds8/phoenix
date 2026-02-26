# HTrace → OpenTelemetry Migration Progress

**Branch:** `feature/opentel/phase1`
**Date:** February 26, 2026

---

## ✅ Completed

### Commit 1: `5c37111` — Batch A (Client Core)
**13 files changed, 1700 insertions, 296 deletions**

| Change | Files |
|--------|-------|
| OTel dependencies added | `pom.xml`, `phoenix-core-client/pom.xml`, `phoenix-core-server/pom.xml` |
| `org.apache.htrace.**` import ban | `pom.xml` (maven-enforcer-plugin) |
| `PhoenixTracing.java` created | New facade over OTel API (follows HBase TraceUtil pattern) |
| `ParallelIterators` | `Tracing.wrap()` → `PhoenixTracing.wrap()` |
| `SerialIterators` | Same |
| `TracingIterator` | Full rewrite: `TraceScope` → OTel `Span` + `Scope` |
| `MutationState` | `TraceScope`/`Span` stubs → OTel types |
| `BaseQueryPlan` | `Tracing.isTracing()` → `PhoenixTracing.isRecording()` |
| `PhoenixStatement` | `Tracing.withTracing()` → `PhoenixTracing.withTracing()` |
| `PhoenixConnection` | Removed `Sampler` field, replaced `TraceScope` with OTel `Span`+`Scope` |
| `TraceQueryPlan` | Rewrote TRACE ON/OFF with OTel span lifecycle |

### Commit 2: `cd65c5c` — Batch B (Server Side)
**7 files changed, 54 insertions, 73 deletions**

| Change | Files |
|--------|-------|
| `IndexRegionObserver` | `Trace`/`Span`/`TraceScope`/`NullSpan` → `PhoenixTracing.createSpan()` |
| `Indexer` | Same + `addTimelineAnnotation` → `addEvent` |
| `PhoenixTransactionalIndexer` | Same pattern |
| `BaseScannerRegionObserver` | `Trace.currentSpan()` → `PhoenixTracing.createSpan()` |
| `LockManager` | `Trace.isTracing()` → `PhoenixTracing.isRecording()` |
| `MetaDataEndpointImpl` | Removed `Tracing.addTraceMetricsSource()` |
| `UpdateStatisticsTool` | Removed `SpanReceiver.class` reference |

---

## 🔴 Pending — Critical Fixes (Span Leaks)

These must be fixed before merging:

| # | File | Issue |
|---|------|-------|
| 1 | `MutationState.sendBatch()` | Span created but `span.end()` never called |
| 2 | `PhoenixConnection.close()` | `traceSpan.end()` missing |
| 3 | `IndexRegionObserver` (2 places) | Span created, scope auto-closed, `span.end()` missing |
| 4 | `Indexer` (2 places) | Same |
| 5 | `PhoenixTransactionalIndexer` (2 places) | Same |

**Fix pattern:**
```java
Span span = PhoenixTracing.createSpan("name");
try (Scope ignored = span.makeCurrent()) {
    // work
    span.setStatus(StatusCode.OK);
} catch (Throwable e) {
    PhoenixTracing.setError(span, e);
    throw e;
} finally {
    span.end();
}
```

---

## 🟡 Pending — Medium Issues

| # | Issue | Files |
|---|-------|-------|
| 6-8 | Unused `TracingUtils` import | IndexRegionObserver, Indexer, PhoenixTransactionalIndexer |
| 10 | `withTracing()` always sets OK | `PhoenixTracing.java` |

---

## ⬜ Pending — Batch C (Trace Infrastructure)

| File | Action |
|------|--------|
| `TracingUtils.java` | Replace stub `Span` → OTel `Span` |
| `PhoenixMetricsSink.java` | Replace `Tracing.Frequency.NEVER` → string constant |
| `QueryServicesOptions.java` | Replace `Tracing.Frequency.NEVER.getKey()` → string constant |
| `TraceWriter.java` | **Remove** (replaced by OTel agent export) |
| `TraceSpanReceiver.java` | **Remove** (replaced by OTel SpanProcessor) |
| `TraceReader.java` | **Remove or simplify** |
| `PhoenixSQL.g` (ANTLR) | Replace `Tracing` import → `PhoenixTracing` |
| `Tracing.java` | **Remove** (replaced by `PhoenixTracing.java`) |

---

## ⬜ Pending — Step 4: Delete Old Files

| Category | Files | Count |
|----------|-------|-------|
| Stub classes | `trace/stub/Span.java`, `Trace.java`, `TraceScope.java`, `Sampler.java`, `SpanReceiver.java`, `Tracer.java`, `HTraceConfiguration.java`, `ProbabilitySampler.java`, `MilliSpan.java`, `TraceCallable.java`, `TraceRunnable.java`, `TimelineAnnotation.java`, `package-info.java` | 13 |
| Old trace infra | `Tracing.java`, `NullSpan.java`, `ConfigurationAdapter.java`, `TraceWriter.java`, `TraceSpanReceiver.java` | 5 |

---

## ⬜ Pending — Step 5: Tests + Documentation

- Rewrite tracing integration tests for OTel `InMemorySpanExporter`
- Update `docs/TRACING.md` with operator guide
- Full build verification: `mvn clean install`
