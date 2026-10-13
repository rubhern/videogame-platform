# Fixture-backed serving/background interference harness

This implemented slice of [#85](https://github.com/rubhern/videogame-platform/issues/85)
validates a reproducible comparison of public serving with acquisition/localization.
Its consumer is the operator preparing the later Lenovo experiment. It belongs to
the existing performance research record; it does not change product operations,
provider policy, architecture, or any query/pool. The accepted stable
[minimum HTTP baseline](http-baseline.md) remains valid evidence of its own run.

## Evidence boundary

The local WSL run is **functional validation only**. Neither HTTP percentiles nor
differences between these scenarios establish capacity, saturation, or a bottleneck.
Machine evidence explicitly sets `capacityInterpretationAllowed: false`; clock
discontinuities are retained rather than repaired or hidden.

The provider fixture reproduces deterministic data semantics and **internal
synchronization cost**: mapping, validation, PostgreSQL writes/projections,
transactions/locks, localization and their overlap with serving. It does **not**
model **external provider latency/cost**, network conditions, retries, throttling
or the operational duration of real IGDB synchronization. Fixture-backed window
durations only identify overlap and choose a bounded calibration input. They must
never become a claim that a number of Games synchronize against IGDB in a given
time. Existing application-side provider pacing remains enabled.

The fixture responds without artificial delays. Removing external network waits
can feed the database/helper faster and produce an **upper-pressure characteristic**
relative to a slower provider; this is a possible harness property, not a measured
representation of IGDB. No realistic latency distribution is invented.

## Scenarios and deterministic input

| Scenario | Public traffic | Manual synchronization | Translation state |
|---|---|---|---|
| `serving-only` | Same frozen read cases | None | Real helper loaded and idle |
| `serving-sync-reuse` | Same frozen read cases | Same fixture payload/window | Completed cache entries; derived links reset so the real flow reuses them |
| `serving-sync-inference` | Same frozen read cases | Same fixture payload/window | Same links reset, selected cache fingerprints pending; real helper infers |

[interference.py](../../../tools/performance/interference.py) owns bounded
calibration candidates, functional rate/duration, controller and evidence mechanics.
The short calibration chooses the smallest candidate with a useful overlap window.
This is an instrumentation input, not a product volume requirement or traffic SLO.

[provider_fixture.py](../../../tools/performance/provider_fixture.py) derives its
Games/metadata and two exact-day Releases per Game from the existing IGDB test
fixtures, following `IgdbFixtureServer`'s standard-library HTTP approach. Provider
IDs start at `850001`; names are `Performance Fixture Mission NN`. Summaries are
`Experiment mission NN. ` followed by the fixture's English Mount Yotei summary.
Every Game has the same `Tactical exploration` genre (`985001`) and `Cooperative
missions` mode (`985002`). Company credits come from the existing details fixture;
PS5/Europe and PC/worldwide Releases use the recorded reference date. Logos are
explicitly empty. No media binary or real provider is fetched.

The input JSON records full synthetic content, source-fixture hashes and a payload
SHA-256. The HTTP ledger records bounded query/response hashes and selected IDs.
Recreating the provider resets that ledger. Comparison checks identical payloads,
actual queries/responses and public cases across scenarios; times are excluded
from that equality check. The fixture rejects unknown paths/IDs and oversized
queries, and has no forwarding mechanism or additional dependency.

The recorded calibration selected **4 Games** (`850001`–`850004`), **8 Releases**,
four distinct normalized summaries of 97 characters each and two shared taxonomy
texts of 20 characters each: **6 fingerprints**, 428 distinct source characters,
and 12 localization target visits per acquisition. The checkpoint contains 50,004
Games, 200,008 Releases and 500,000 Ratings. Both serving/background scenarios
resynchronize those existing four Games and eight Releases; this slice exercises
idempotent acquisition plus localization, not a large new-import workload.

The functional serving workload reuses all twenty
[growth public-read cases](growth-read-load.md#exact-workload-and-recorded-observation): nine release cases, seven
search cases and four detail fan-outs. Each case receives three requests in the
same deterministic rotation: 60 GETs at an experimental 1 request/s, with four
bounded VUs. There are twenty preflight GETs per scenario outside that workload.
Checks require valid responses, echoed correlation, expected cohorts/fan-outs,
zero unexpected failures/timeouts and no dropped iterations. No latency gate or
mandatory CI gate is added. The background is dispatched after serving starts.

## Preparation, repetition and restoration

Prerequisites are the supported local stack/application image, existing k6 image
and an approved converted OPUS model. The model installer and helper are reused
from [catalogue-localization](../../../tools/catalogue-localization); the runner
does not download models. Start with the optional helper stopped and cron disabled.
For this observation the old helper mount was empty; the approved model was
installed using its existing image/installer into an ignored local model directory.

```bash
bash tools/performance/dataset.sh generate growth 2026-10-07
python3 tools/performance/interference.py run \
  --runtime-checkout /path/to/supported/runtime-checkout \
  --model-dir /path/to/approved/converted-model
```

Use the current application evaluation date on later runs. `run` prepares the
fixture and real-localizer calibration, runs each scenario, and restores the
persistent application in `finally`. For separate controlled phases use `prepare`
with the same arguments, then `scenario RUN_DIRECTORY SCENARIO`, followed by
`restore RUN_DIRECTORY`. Never overwrite a scenario's evidence directory.

Condense a completed run with:

```bash
python3 tools/performance/interference.py report RUN_DIRECTORY \
  --output /path/to/interference-evidence.json
```

This rechecks actual provider/public input equality, serving/background overlap and
persistent cardinalities before writing the report. The frozen observation is
[interference-evidence.json](interference-evidence.json).

Calibration acquires the bounded subset into the disposable `growth` database and
resolves its translations with the real helper. A custom-format PostgreSQL dump
then freezes that state. Every scenario stops the application, restores the same
checksum-verified checkpoint, applies
[interference-state.sql](../../../tools/performance/interference-state.sql), and
recreates the application/helper/provider with the same images/resources. Offline
`ANALYZE` recreates statistics omitted by `pg_dump`, and `CHECKPOINT` settles restore
writes before measurement. Only the selected translation completion fields differ
between reuse and inference; source
rows, provider references and acquisition payload are unchanged. Summary links and
the two machine taxonomy links are reset so completed content exercises `REUSED`
rather than silently skipping every target. This SQL is offline experiment setup,
not a production migration, switch or optimization.

The wrapper reuses `dataset.sh`'s local-engine/container/anonymous-volume guards;
SQL independently requires the disposable database, marker, matching schema and
bounded resolved checkpoint before mutation. Flyway and cron are disabled. The
temporary Compose overlays replace provider credentials/URLs with fixture-only
values. Secrets stay in the original runtime env files. No persistent catalogue
is dumped, reset or restored. Restoration reconnects the original application,
checks readiness/cardinalities, stops the optional helper, and removes the verified
provider/disposable database and its anonymous volume. Raw evidence/checkpoint stay
under ignored `tools/performance/results/` for inspection.

## Captured signals and interpretation gaps

The existing k6 script retains throughput, per-case p50/p95/p99, checks, HTTP
failures, timeouts and dropped iterations in JSON. Existing application meters
retain JVM heap/GC/threads/process CPU, Hikari activity/pending/timeouts, provider
request/retry counters and localization outcome counters. Full before/after
snapshots reuse the baseline sampler, and bounded application/helper logs remain
with raw evidence. Real inference is corroborated by helper completion logs,
`translated` meter deltas, completed fingerprints and the installed runtime revision;
reuse is corroborated by zero helper completions, zero translated delta and positive
`reused` delta. Synchronization success alone is insufficient.

Light samples read cgroup CPU use/throttling, charged memory, OOM events and block
I/O for application, PostgreSQL and localizer. Read-only SQL captures grouped
`pg_stat_activity`, `pg_locks`, database counters, running status/heartbeat and final
sync report. A quarter-second paired wall/monotonic audit accompanies k6 timestamps.
Controller dispatch/acknowledgement and serving launch/finish use monotonic stamps;
public URI completion counters bracket background overlap. These are controller
windows, including HTTP transport, not precise internal transaction boundaries.

Sampling is best effort: collection time adds to the nominal one-second interval,
and transient locks or short inference bursts can fall between samples. Logs give
real inference counts/durations but no controller-aligned per-inference start/end.
The synchronization heartbeat is not a durable per-Game progress counter. Cgroup
I/O does not measure filesystem-cache hits or whole-host/device contention, and
cgroup memory includes charged file cache rather than process RSS alone. Logical
checkpoint equality does not promise equal host caches or physical storage layout.
No whole-host
Windows/WSL CPU/RAM/IO, query-plan traces or authoritative provider timing is added.
Management sampling itself consumes some resources. These gaps and the unstable
WSL clock prevent capacity attribution from this local validation.


## Evidence and conclusion

The [final report](remote-harness.md) owns capacity conclusions.
The [interference-evidence.json](interference-evidence.json) retains this method’s historical results.
Clock-invalid local runs remain functional/query diagnostics only; they are not capacity evidence.
Raw artifacts remain ignored under `tools/performance/results/`.
