# Gameómetro performance: final assessment of #85

## Executive conclusion

**Decision: no action for current private operation.** The modular monolith is healthy
at the tested normal 10 req/s and at 12 req/s for five minutes on Lenovo. At a requested
15 req/s, connection-pool acquisition saturates and user requests degrade. Removing
stress restores healthy responses without recreating the app or database.
This is enough to close the measurement investigation; it does not justify an
architecture change or promise continuous public-production capacity.

Numerical truth is the [frozen capacity evidence](remote-capacity-evidence.json),
including `completion`; exact script/raw hashes, image/model identities, per-case
results and restoration checks remain there. [Harness operations](../../../tools/performance/README.md)
own reproduction and safety. No additional experiment is required for #85.

## Environment and methodology

- Lenovo: Intel i7-7700HQ, four physical/eight logical cores, approximately 7.63 GiB
  RAM, 4 GiB swap, Ubuntu 24.04, ext4. Allocated app: 1.5 CPU/1536 MiB;
  PostgreSQL: 1 CPU/768 MiB; OPUS/CT2 localizer: 2 CPU/768 MiB. Hikari maximum: 10.
- Deterministic `growth`: **50,000 Games, 200,000 Releases, 500,000 Ratings**,
  4,020 synthetic rating owners. Synchronization prepares an additional 64 fixture
  Games/128 Releases before measurement; mixed adds two temporary identity accounts.
  These distributions are experimental inputs, not forecasts of actual users.
- Native Windows k6 2.3.0 generates private HTTPS traffic externally. Independent
  wall/monotonic clock audits pass for the accepted Lenovo measurements.
  The normal public mix rotates 17 release/search/detail cases with bounded pages;
  adversarial adds broad/deep searches. No browser rendering, assets or CDN traffic.
- Completion SUT: `0b3d26672da0a5bcda711791bfeb02c53637693c`, `0.29.1-SNAPSHOT`.
  The five-minute reference uses `34a8d8ea` (full identity in JSON); only harness/research
  files changed between those sources, but image build and physical/cache state differ.
  Older adversarial evidence predates the current transaction-error handling.
- Predeclared progressive stress: 10/15/20/25 req/s, 60 seconds/level, 30 VUs,
  stop at first >=1% errors/drops, <95% scheduled delivery or p95 >1 s.
  These gates are experimental criteria, not product SLOs. Only 10 and 15 ran.
  Five-minute existing evidence suffices here; no extra soak or extreme-limit search.

## Results by scenario

Latencies below are global milliseconds. Delivered rate includes the measured
completion interval. Zero HTTP timeouts occurred in every row; dropped iterations
are generator work that never reached the server. Mixed expected redirects/403/404
are functional checks, not errors.

| Scenario / run suffix | Offered load and duration | Responses / delivered req/s | p50 | p95 | p99 | HTTP errors / drops |
|---|---|---:|---:|---:|---:|---:|
| Normal serving `ar4bukx5` | 12/s, 300 s | 3,600 / 12.00 | 45.80 | 145.25 | 226.06 | 0 / 0 |
| Normal serving `eqb_g53p`, level 10 | 10/s, 60 s | 600 / 10.00 | 51.69 | 149.97 | 325.44 | 0 / 0 |
| Sync reuse `rwkiahu4` | 10/s, 60 s | 600 / 10.00 | 50.08 | 142.12 | 503.69 | 0 / 0 |
| Sync real inference `lqhojz65` | 10/s, 60 s | 600 / 10.00 | 53.46 | 157.89 | 1,009.30 | 0 / 0 |
| Concurrent public/auth/sync `56_70s8z` | 2 auth users + 120 public reads | 304 / 4.52 | 16.44 | 118.90 | 277.69 | 0 / 0 |
| Normal stress `eqb_g53p`, level 15 | 15/s, 60 s | 815 / 13.25 | 93.30 | 3,445.27 | 4,403.94 | 10 × 503 / 86 |
| Same-instance recovery `eqb_g53p` | 5/s, 60 s | 300 / 5.00 | 46.99 | 123.72 | 151.72 | 0 / 0 |
| Historical adversarial `8bp42ph4` | 5/s, 60 s, 4 VUs | 269 / 4.45 | 203.50 | 3,437.80 | 4,510.70 | 1 × 503 / 32 |

The authenticated mix passed 1,156 checks, two real hosted Keycloak/BFF logins,
eight session reuses and ten workload creates, updates and deletes each. Secure
cookies, CSRF rejection and ownership remained active. Experimental rating count
and full-row aggregate fingerprint matched before/after; synthetic accounts were removed.

## Lenovo capacity and practical users

**Measured healthy range:** normal public reads at 10–12 req/s in the stated windows.
At 12/s, all 3,600 responses and 14,400 checks passed; Hikari sampled active maximum
was three, pending zero and acquisition timeouts zero. Twelve req/s is not a 24/7
safe capacity guarantee. At requested 15/s the measured workload degraded; this is
not a universal limit for every endpoint, dataset, cache state or concurrency setting.

**Orientative estimate:** the Lenovo appears viable for hundreds of registered or
daily active users with moderate, distributed traffic. Registered users do not
continuously send requests. For 400 hypothetical users simultaneously making one
API request per minute, average demand is 6.67 req/s; one per 40 seconds is 10/s;
one per 30 seconds is 13.33/s. A page requiring three calls every minute triples
the first figure to 20/s. Bursts, login/writes, assets and query mix change demand.
**No test established 400 concurrent sessions**; only two authenticated users were
measured simultaneously. Daily-active counts alone cannot imply request capacity.

Recovery at 5/s passed 1,200 checks with pending connections zero and no new pool
timeouts. Readiness, JVM, database and resources recovered without instance reset.
After every run private-dev restored its original configuration/scheduling, unchanged
private catalogue/ratings and dependency identities. Final verification: readiness UP;
21,036 Games, 37,529 Releases, 22 Ratings, 27 sync records/zero running; zero
experimental databases, helper containers or synthetic accounts. Audit receipts remain;
prior sessions and PostgreSQL caches/WAL are not rolled back to their pre-test state.

## Bottlenecks and resource evidence

**Demonstrated:** at 15/s Hikari reached ten active and fourteen pending, with ten
acquisition timeouts, `SQLTransientConnectionException` and ten HTTP 503s. All 86
drops coincided with at least 29 reconstructed requests in flight out of 30 slots;
generator CPU was low. Slow responses consumed VUs, reducing delivered traffic.

**Correlated, causal contribution unproven:** PostgreSQL used 48.95 CPU seconds in
the stress observation bracket and throttled 367/644 periods under its one-CPU quota.
SQL/connection occupancy and quota pressure are plausible causes of pool saturation;
per-query connection hold time and a controlled quota comparison were not measured.
Increasing Hikari could increase database contention rather than fix this condition.

**Query-specific evidence:** broad/deep adversarial search ran for several seconds;
`search_deep_48k` produced a 5.24 s 503. PostgreSQL recorded 65 temporary files/357 MB.
A disposable local replay of production SQL showed underestimated broad cardinality,
a 50,000-iteration nested loop, spilled aggregation and external sorting. This is
useful evidence for focused SQL work, not the exact Lenovo execution plan. Autovacuum,
workload and preparation differed between historical comparisons, limiting attribution.

| Completion phase | App / PG CPU seconds | App / PG peak charged MiB | GC pauses / total ms | Hikari active / pending max; new timeouts |
|---|---:|---:|---:|---|
| Inference | 21.13 / 27.58 | 455.50 / 764.29 | 5 / 299 | 0 / 0; 0 |
| Mixed | 19.80 / 8.65 | 453.88 / 764.22 | 4 / 242 | 0 / 0; 0 |
| Serving 10 | 20.66 / 25.16 | 454.98 / 764.54 | 5 / 355 | 1 / 0; 0 |
| Serving 15 | 14.78 / 48.95 | 465.38 / 761.12 | 5 / 37 | 10 / 14; 10 |
| Recovery | 7.45 / 13.14 | 467.89 / 759.46 | 2 / 5 | 1 / 0; 0 |

CPU brackets include observation overhead; charged memory includes file cache and
sampled peaks can miss short events. No OOM, sampled ungranted lock, deadlock or
SQL cancellation occurred in completion runs; no temporary files in normal stress.
Database I/O/counter deltas and host pressure are frozen in JSON. Recovery PostgreSQL
CPU fell to 13.14 s, with 5/615 throttled periods and zero new memory-limit events.
Heap stayed below its configured ceiling; increased thread count did not accompany
persistent degradation. Neither throttling nor memory-limit events alone prove a
bottleneck: healthy 10/s had more memory-limit events than degraded 15/s.

## Synchronization and localization

Reuse refreshed **64 existing fixture Games/128 Releases in 43.05 s**, about
**1.49 Games/s** (approximately 1.48 at this precision); inference took 43.30 s,
about 1.48 Games/s. Each used 130 fixture calls with application pacing enabled;
about three calls/s explains most wall time despite immediate fixture responses.
This is an interpretation of the request budget and duration, not a latency-isolation experiment.
They were already acquired during preparation: measured work was refresh/reconciliation,
not importing 64 new Games. No fixture call reaches IGDB.

Reuse had 66 reused targets/zero inference. Real OPUS/CT2 EN→ES inference completed
four fingerprints, 62 reused targets and zero failures; four helper completions
corroborate translated counters. Four inferences do not represent mass localization,
a cold model or long unique summaries. Concurrent HTTP showed no errors; the table
reports tail latency, without attributing its differences to inference causally.
Payload/source/public workloads match reuse, but physical/cache states do not.
Actual overlap was approximately 43 s. Reuse accounts for all 600 requests:
54 before + 430 during + 114 after + **2 boundary-crossing** requests, not 598 lost samples.

A separate normal-rate IGDB sample made only three one-row reads: games 771.28 ms,
release_dates 323.32 ms, platforms 216.36 ms, all HTTP 200, no retries. Provider
limits and application pacing bound acquisition; these tiny reads do not predict
rich-query latency, full sync duration or the true import/localization bottleneck.
**We do not know the real throughput of importing and translating large numbers
of new Games from IGDB.** Provider calls, retries, unique text and persistence all matter.

## Prioritized options and final decision

| Potential future work | Evidence / priority | Cost and decision |
|---|---|---|
| Grafana alerts, #269 | Observed pool/error/latency/resource signals; operational value | Existing telemetry, no recurring cost; separate issue, not implemented here |
| Broad/deep search SQL and connection-hold analysis | Material slow queries/pool failure if higher traffic becomes a requirement | Focused software experiment first; no index/pagination change approved |
| CPU allocation or RAM/storage assessment on existing hardware | PG quota correlated with stress; memory/IO are not proven constraints | Controlled comparison only if required; no quota/hardware purchase approved |
| New-game IGDB/localization throughput | Unmeasured acquisition case | Bounded provider-respecting experiment only for an explicit backfill target |

Do not automatically increase Hikari, parallelize sync, add cache/Redis, services,
virtual threads or infrastructure. There is no demonstrated architecture failure.
Return to functional development. These possibilities are hypotheses, not approved work;
[#269](https://github.com/rubhern/videogame-platform/issues/269) owns alert follow-up.

## Acceptance and traceability

All ten #85 criteria have PASS evidence: predeclared scale/gates; repeatable public
and authenticated paths; mixed sync pressure; observed bounded saturation; recovery;
HTTP plus JVM/DB/host attribution signals; no abusive IGDB traffic; concise reproducible
report; no speculative optimization; no speculative follow-up backlog. Relevant run
IDs and hashes are retained in JSON. Historical functional/clock-invalid results are
preserved in linked evidence, excluded from capacity claims. Harness tests, documentation
validation and publication CI establish delivery acceptance; no product deployment is required.
