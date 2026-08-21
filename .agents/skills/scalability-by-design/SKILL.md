---
name: scalability-by-design
description: Review or design VideoGame Platform API endpoints, database queries, repositories, persistence, pagination, lists and collections, external integrations, synchronization, caching, metrics, concurrency, batch processing, and read models for bounded work and evidence-based scalability. Use before implementing or modifying any such path. Do not infer scale from fixtures or introduce distributed infrastructure without a measured trigger.
---

# Scalability by Design

Design for large growth without defaulting to distributed systems. Repository
authorities and approved product/architecture decisions remain binding.

## Required review

Complete this workflow before implementation and revisit it during final review.

### A. Characterize scale

Identify qualitatively, and quantitatively where evidence exists:

- dataset cardinality and growth axis;
- request rate and concurrency;
- response and batch size;
- historical/storage growth;
- expected fan-out and external network work.

Never treat current fixtures as expected scale. “Works with current fixtures” is not
scalability evidence.

### B. Identify the hot path

Trace `request -> delivery -> application -> database/provider -> response`. Mark
operations whose cost grows with the complete dataset, history, user count, or
unbounded input.

### C. Enforce bounded work

Answer:

- how many rows are scanned and returned;
- how many objects and bytes cross datastore/application boundaries;
- how much data is sorted and retained in memory;
- what bounds the request, batch, retry, and fan-out.

If a page of 20 materializes 100,000 rows in the application, stop and redesign.
Per-request application memory should normally follow the page/result/batch size.

These patterns are prohibited for persistent paginated endpoints:

```java
repository.findAll().stream()
    .filter(...)
    .sorted(...)
    .skip(...)
    .limit(...);
```

```java
snapshot.releases().stream()
    .filter(...)
    .sorted(...)
    .toList();
```

### D. Push work to the datastore

Put filtering, search, ordering, aggregation, counting, and pagination in PostgreSQL
when it is the approved source capable of doing that work efficiently. Use explicit
SQL when it makes the access path clearer. Keep business meaning and policy in the
domain/application layer; persistence may store derived query boundaries.

### E. Choose the query model

Decide whether the aggregate repository fits. Prefer an application-owned read port
or rebuildable projection when the query needs a bounded shape different from the
write model. This is logical CQRS, not permission for another deployable or datastore.

### F. Require total ordering

Every paginated query needs a deterministic total `ORDER BY`. The final tie-breaker
must uniquely identify one result row. Test ties across every earlier key and page
boundary.

### G. Protect integrity and concurrency

Review constraints, foreign keys, unique indexes, transactions, concurrent writes,
races, idempotency, and optimistic/pessimistic locking as applicable. Do not hide
join multiplication or invalid states with `DISTINCT` or arbitrary first-row logic.

### H. Inspect real query plans

For hot or materially changed queries, use representative data and inspect `EXPLAIN
(ANALYZE, BUFFERS)`: scans, rows removed, sort, buffers, estimates, and execution
time. Add or retain indexes only when the plan demonstrates value. Do not make shared
CI latency a brittle performance threshold.

### I. Review caching

Check HTTP cache semantics, validators, `Cache-Control`, and representation-changing
inputs before adding application caching. A cache is an optimization, never the only
source of correctness. Do not add a cache merely because the dataset may grow.

### J. Preserve horizontal scaling

Correctness must not rely on mutable process-local state, sticky sessions, or one
application instance. Identify consistency requirements if a future read replica is
considered, but do not add one speculatively.

### K. Bound observability

Use bounded metric-label vocabularies and bounded log/trace attributes. Never use
arbitrary request values or game, release, user, request, or correlation IDs as
metric dimensions. Bound telemetry volume and payload size.

### L. Reject speculative infrastructure

Before proposing Redis, Kafka, Elasticsearch, microservices, another database,
distributed caches, or Kubernetes components, answer:

1. What measured limitation does it solve?
2. Why are PostgreSQL and the current stateless application insufficient?
3. What operational and consistency complexity does it add?
4. What explicit trigger or bounded experiment authorizes it?

If these answers are not solid, use correct algorithms, models, SQL, indexes,
constraints, HTTP caching, statelessness, and horizontal application scaling first.

### M. Validate regressions

Add fast deterministic tests for bounds, ordering, edge cases, integrity, and
concurrency at the appropriate seam. Add an opt-in reproducible scale/query-plan
tool when normal CI should remain small. During final review, search the request path
for full-dataset loading, in-memory paging, unbounded fan-out, high-cardinality tags,
and local mutable correctness state.

## Required handoff

Report expected cardinality, time/memory/database/network work, ordering and
pagination strategy, integrity/concurrency decisions, query-plan evidence,
cacheability, horizontal-scaling behavior, failure mode, and remaining evidence-based
triggers. Keep future risks as explicit follow-ups rather than speculative
infrastructure.
