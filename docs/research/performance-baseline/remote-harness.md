# Windows generator / Lenovo functional preparation

This slice of [#85](https://github.com/rubhern/videogame-platform/issues/85)
reuses the existing public, authenticated and mixed k6 modules, synthetic SQL and
fixture provider. It establishes remote functional evidence and three bounded public
capacity pilots, with no sustained-capacity conclusion. It adds no
product code, migration, contract, exporter, mandatory CI gate or releasable
artefact version increment. This record owns the experiment method and evidence;
the [private-dev procedure](../../../deploy/private-dev/README.md#owner-connection-from-wsl)
owns SSH access and deployment operations.

## Implemented adapter and connection

The controller runs in WSL; **k6 runs as a native Windows process**, outside WSL
and Docker Desktop. Windows reaches the existing private Tailscale HTTPS origin
and real Keycloak issuer, using their registered hostnames, ports and certificates.
No DNS override, insecure TLS option, bearer-token workload or authentication
bypass is introduced. Actuator, PostgreSQL and administrative Keycloak access
remain internal and are observed through the owner's SSH alias.

[remote.py](../../../tools/performance/remote.py) owns orchestration,
[remote_worker.py](../../../tools/performance/remote_worker.py) owns the host
adapter, and [windows-k6.ps1](../../../tools/performance/windows-k6.ps1) owns the
portable Windows download, published ZIP checksum, binary/version receipt and
native process. Its pin matches the existing k6 image pin. No administrator/MSI
installation or permanent PowerShell policy change is needed. The launcher copies
trusted scripts to Windows local application data and uses process-scoped
`RemoteSigned`. Credentials are held in a separate directory whose ACL admits only
the current Windows identity and SYSTEM, and are removed in `finally`. Only newly
created synthetic passwords cross SSH; existing secrets and administrative tokens
stay on the Lenovo. Callback URLs, cookies, headers and CSRF material are excluded
from retained k6 output.

From the new worktree in WSL, with Windows interop, the existing SSH alias and
Tailscale connected on both machines:

```bash
python3 tools/performance/remote.py install
python3 tools/performance/remote.py plan --revision 8cb6071127b65c78fc74609502df4b4189b055d2
python3 tools/performance/remote.py probe --revision 8cb6071127b65c78fc74609502df4b4189b055d2
```

From Windows PowerShell, the same controller can be invoked through `wsl.exe`:

```powershell
wsl.exe --cd /home/rubhern/.codex/worktrees/issue-85-native-remote/videogame-platform python3 tools/performance/remote.py install
& "$env:LOCALAPPDATA\GameometroPerformance\k6\2.3.0\k6-v2.3.0-windows-amd64\k6.exe" version
```

The WSL path identifies this review worktree; use the reviewed checkout path on a
later run. WSL handles SSH/orchestration only; the generated requests and generator
clock audit come from the Windows executable.

`install` verifies the official portable Windows release. `plan` inspects the
host without modifying databases, accounts or services. `probe` sends fifteen
public requests at one request/s against current data and captures server metrics.
Each command creates a new ignored evidence directory under
`tools/performance/results/remote-*`; `install` prints its binary receipt.
The full deployed SHA is checked against both OCI labels and Actuator build info.
The checked-out harness revision and script hashes are recorded separately.

The controller uses the existing deployment lock and persistent JSON RPC over SSH,
with no listener or additional infrastructure. It stages only harness code,
immutable migrations and provider test inputs in an owner-only temporary folder;
it neither changes nor executes from the protected deployment checkout.

## Approved shared-environment window and restoration

A separate database isolates **data**, but shares PostgreSQL CPU, memory, WAL,
I/O and caches. The existing same-origin app is temporarily recreated against that
DB: current BFF sessions are interrupted and the existing private URL temporarily
serves synthetic data. A maintenance window without normal users is required.
The owner approved this window for the recorded smoke. A future window needs its
own owner authorization; the CLI acknowledgement alone does not grant permission.

```bash
# Only within an explicitly approved maintenance window:
python3 tools/performance/remote.py smoke \
  --revision 8cb6071127b65c78fc74609502df4b4189b055d2 \
  --approved-maintenance-window
# After interruption, using the exact retained evidence directory:
python3 tools/performance/remote.py recover tools/performance/results/remote-EXACT-RUN
```

Preflight rejects a changed app revision, mutable image, unexpected Compose
overlay, unhealthy dependency, active sync or an existing experiment database.
The adapter requires filesystem headroom and matching immutable migration SQL.
It creates only an absent `videogame_performance` database in the existing server,
records its OID, and reuses the unmodified SQL generator with its database,
marker and schema-checksum guards. It does not create roles or copy personal data.
The `growth` profile/date determines catalogue rows and background synthetic
rating owners; Keycloak principal IDs and passwords are newly generated per run.

Two fixed synthetic usernames, disjoint three-Game assignments and two iterations
per user reuse the [authenticated journey](authenticated-harness.md). An existing
username is refused. Creation uses the existing management-only provisioning seam
and already configured admin-only ownership marker. The creation response ID and
normalized synthetic email are persisted before follow-up checks. Accounts are
removed only if the immutable ID, email and marker still match. No realm, identity
DB, existing account, group or role configuration is replaced.

Only `application` is recreated, with the same immutable digest, CPU/RAM limits,
TLS origins, issuer and secret references. Flyway and schedules are disabled;
real-provider credentials point to `/dev/null` and provider endpoints to a closed
loopback port. PostgreSQL, Keycloak, localizer, telemetry and their volumes are not
recreated. No real IGDB synchronization is dispatched.

Restoration reconnects private-dev using its original protected base Compose and
runtime inputs **before** administrative account cleanup. It restores scheduling,
checks readiness, private catalogue/ratings aggregates and rating fingerprint,
and verifies unchanged dependency container identities. The owned database is
removed without `FORCE`; a replaced OID is refused. Ratings written by an unexpected
principal cause retention of the experiment DB for owner review while restoring
private-dev. Receipts remain available for recovery, including on SSH EOF. A
failure or ambiguous ownership requires inspection rather than blind deletion;
preflight prevents an unrelated new experiment from adopting leftovers.

## Exact short smoke

Two authenticated slots start at 0 and 55 seconds; the public slot starts at 110
seconds. Each authenticated slot has one VU and two iterations, with a one-second
pause after each request and a 55-second maximum. Public traffic is ten requests
at one request/s. This intentionally avoids concurrent user bursts and is a mixed
composition proof, not a simultaneous-user capacity observation.

| Portion | Requests | Cases |
|---|---:|---|
| Authentication/session | 10 + 4 | Two hosted Keycloak logins (five steps each), four session reads; second iterations reuse cookies |
| CSRF rejection | 2 | Missing-CSRF writes return the expected 403 |
| Prepare/restore | 4 + 4 | Two independent seed ratings per user, then conditional deletion |
| Authenticated journeys | 60 | Four journeys × fifteen requests, excluding the session reads above: list/sorts/title and alias search, scoped absence, create/read/update/read/list/delete/absence/list |
| Logout | 4 | Two BFF logouts and two unauthenticated session reads |
| Public reads | 10 | Two each: recent, upcoming, featured, Adventure search, detail |
| **Total** | **98** | Expected statuses include redirects, 403 and 404; these are checked expected outcomes |

The workload checks real secure opaque BFF cookies, session rotation, minimal
session response, CSRF, conditional validators, personal values and list ordering.
It never calls a product OAuth token endpoint or models browser-held OAuth tokens.
Each user writes only its own ratings. Because the slots are serialized, peer
absence does not establish a simultaneous cross-user isolation proof; the earlier
local authenticated evidence retains that separate coverage. The existing contract
has no personal platform/region filter, so none is added.

## Capture and configuration boundary

Before/after and periodic snapshots use existing Actuator meters for HTTP, JVM
heap/GC/threads/process CPU, Hikari active/pending/max/timeouts and localization.
Read-only PostgreSQL observations capture transactions, buffer hits/reads, temporary
files, deadlocks, grouped activity and ungranted locks. Docker stats and cgroup
CPU/throttling, memory/OOM and I/O are retained, alongside physical `/proc/stat`,
`meminfo`, `diskstats` and CPU/memory/I/O pressure. No external exporter or public
management port is needed.

Windows pairs UTC with `Stopwatch`/QPC; Lenovo independently pairs realtime with
monotonic time every approximately 250 ms. k6 timestamps receive the existing
HTTP `clockAudit`. Backsteps and wall/monotonic discontinuities are preserved.
Clock validity and functional checks are separate observations. A stable short
window does not guarantee clock stability during a later longer run.

Snapshots incur SSH/Docker/SQL overhead and their nominal five-second spacing
includes collection time. Very short waits, GC or inference may fall between
samples; ungranted locks are server-wide. Cgroup memory includes charged file
cache and I/O counters do not include cache hits. The existing Grafana SQL source
continues to target the original database; experiment SQL statistics come from
SSH observations, while existing application telemetry continues to flow to the
original observability stack. No dashboard datasource is changed.

Since the second capacity pilot, `stop_observation` also keeps, before restoration
discards the experiment container, the application's WARN/ERROR events for the
observed window with allowlisted ECS fields only (`error.code`, `error.type`,
`error.root_cause_type`, `error.sql_state`, correlation and trace identifiers;
free text only from the redacting API exception handler), in
`application-errors.json`. Because PostgreSQL is shared with private-dev, its log
yields only counts of statement cancellations, temporary-file, `ERROR` and `FATAL`
lines; statement text never leaves the host.

The [frozen remote evidence](remote-evidence.json) records physical resources,
unchanged limits, relevant JVM/Hikari/PostgreSQL/localizer configuration, observed
smoke metrics, clock audits and restoration. Raw files remain local and ignored;
private endpoints and protected inputs are excluded from tracked evidence.

The accepted run completed **98 requests, 350 checks and zero unexpected HTTP
failures, timeouts or dropped iterations** at 0.809 requests/s overall. Both users
authenticated and reused their sessions; four workload creates, four updates and
four deletes passed, as did every personal-list/search/sort check. Global observed
p50/p95/p99 were 17.16/117.48/373.56 ms; the small per-case samples are functional
observations only. Windows retained 458 clock samples and Lenovo 497, with no
backsteps or discontinuities; raw k6 retained all 98 HTTP timestamps.

The application HTTP counter increased by 94, matching the 98 generator requests
minus four hosted Keycloak requests. Fifteen server snapshots captured three GC
pauses totalling 232 ms, Hikari max 10 with no observed pending connections or
timeouts, and PostgreSQL activity/buffer/temp-file/lock counters. The memory-limit
event counter for PostgreSQL increased without OOM; charged memory includes cache
and fixture-generation effects. No capacity or bottleneck conclusion follows.
The localization meter was unavailable without synchronization activity; no
inference evidence is claimed. Prometheus readiness and its two scrape targets,
Grafana/database health, Loki readiness and collector metrics were verified after
restoration. Alloy was running; its internal readiness was not sampled.

Restoration removed both accounts and the experiment DB. Private counts remained
20,999 Games, 37,480 Releases and 22 Ratings, with the same rating fingerprint,
26 historical sync runs and none running. Scheduling returned to enabled; all
dependency identities and CPU/RAM limits were unchanged. Temporary owner-only
code/ownership receipts remain for audit/recovery. PostgreSQL caches/WAL and old
BFF sessions are not restored to their pre-window process state.

Failed launcher attempts are retained, not counted as accepted runs. Inspection
initially lacked explicit k6 environment paths, and a public probe was rejected
because PowerShell returned no exit code. Both controls were corrected and the
accepted public probe passed 15 requests/60 checks. The first growth preparation
stopped because Keycloak normalized email to lowercase before the account ID
receipt was saved; its owned account was verified and removed, private-dev never
switched, and the disposable DB was removed. Email normalization and immediate ID
receipts are now covered by a focused regression check. The full successful smoke
followed that correction; failed raw evidence remains separate.

## Public capacity pilots

`capacity` requires `--workload-profile normal|adversarial`; the
[growth workload](growth-read-load.md#exact-workload-and-recorded-observation) owns
both profiles and their exact distribution. `--vus N` sets equal pre-allocated and
maximum VUs, defaults to the original 4, and is refused outside
`1..min(60, rate × 12)`. Constant arrival needs about rate × worst iteration
duration; the k6 timeout bounds that at rate × 10 s. After activation and any
background preparation, `settle` runs `VACUUM (ANALYZE)` on the owned experiment
database only, then requires 30 consecutive seconds without an autovacuum worker or
vacuum progress there, refusing to measure after 300 seconds. `settle.json` keeps
the largest tables' vacuum/analyze debt before and after.

```bash
# Only within an explicitly approved maintenance window; run plan first.
python3 tools/performance/remote.py capacity \
  --revision 8cb6071127b65c78fc74609502df4b4189b055d2 \
  --approved-maintenance-window --scenario serving-only \
  --workload-profile normal --vus 4 --rate 5 --duration-seconds 60
```

The first two owner-approved pilots used the same SUT digest and limits (application
1.5 CPU / 1536 MiB, PostgreSQL 1 CPU / 768 MiB, Hikari 10, `work_mem` 4 MB,
`shared_buffers` 128 MB), regenerated `growth` data, native Windows k6 2.3.0 at
5 req/s for 60 seconds with 4 VUs, and no synchronization, inference or schedules.
[remote-capacity-evidence.json](remote-capacity-evidence.json) freezes per-case
percentiles, resources, clock audits and restoration for both; raw evidence remains
ignored. Each restoration reconnected private-dev with unchanged rating
fingerprint, no running sync, unchanged dependencies and readiness `UP`.

| Observation | `8bp42ph4`: `adversarial`, 20 cases | `bhet96wm`: `normal`, 17 cases |
|---|---:|---:|
| Scheduled / completed requests | 300 / 269 (4.45/s) | 300 / 300 (5.00/s) |
| Dropped iterations | 32 | 0 |
| HTTP errors / timeouts / failed checks | 1 × 503 / 0 / 3 | 0 / 0 / 0 |
| Global p50 / p95 / p99 | 203 / 3,438 / 4,511 ms | 52 / 132 / 183 ms |
| Mean in-flight requests (reconstructed) | 2.51; all 4 VUs busy 18% | 0.28; never more than 2 |
| PostgreSQL CPU / throttled periods | 57.2 s / 529 of 629 | 14.0 s / 11 of 621 |
| PostgreSQL temp files / bytes | 65 / 357 MB | 0 / 0 |
| PostgreSQL memory-limit events / OOM | 953 / 0 | 199 / 0 |
| Application CPU / Hikari active max, pending | 16.6 s / 4, 0 | 16.0 s / 1, 0 |
| Lenovo busy logical CPUs (of 8) | 1.58 | 0.92 |
| Autovacuum during load | `VacuumDelay` observed | none; settled first |
| Clock backsteps / discontinuities | 0 / 0 | 0 / 0 |

In `8bp42ph4`, every dropped iteration occurred with all four VUs busy, 31 of 32
with `search_deep_48k` in flight, so the server never received the full 5 req/s.
Its only 503 was `search_deep_48k` at 5,238 ms. The catalogue read transaction and
JDBC statement timeout are both 5 s (`catalogue.jdbc.read-timeout`), the server
maximum was 5,234 ms, `xact_rollback` rose once in the matching window and Hikari
reported no pending connection or timeout. The k6 checks show an
application-generated `CATALOGUE_READ_FAILED` Problem; that run predates log
capture, so SQLSTATE `57014` remains unconfirmed. A disposable local replay of the
production search SQL on identical `growth` data showed a 1,000-row estimate for
50,000 matching Games, a 50,000-iteration nested loop, a spilled hash aggregate
and a 10.8 MB external sort for both deep offsets; per-run temp volume matches
those plans within about 10%.

In `bhet96wm`, release, accented/normalized search and `detail_fanout_1` p50s among
the 17 shared cases fell 2–6 times (for example `releases_recent` 202 → 52 ms,
`search_normalized` 406 → 76 ms); selective, alias and the other detail p50s stayed
at about 15–25 ms in both runs. No WARN/ERROR
application event, statement cancellation or temporary file was observed.

**Attribution.** Removing the three cases is the most likely main cause:
PostgreSQL CPU fell from 0.83 to 0.20 CPU over the window and its quota stopped
throttling, which also removes queueing for the shared cases. The comparison is
not controlled: the workload **and** the settle step changed together, and
`8bp42ph4` overlapped autovacuum of freshly generated tables. Evaluation dates
differ (2026-10-12/13), so release windows and fixture dates move. Private-dev
received one scheduled sync between runs and shares PostgreSQL. Caches were not
reset and the harness bytes differ (recorded per run). Per-statement CPU, wait
time and plans were not captured on the Lenovo.

**Limits of the result.** `normal` at 5 req/s is a light 60-second load with
under one request in flight on average; it shows no generator limitation and no
saturation, and does not establish sustained, peak or production capacity.
`adversarial` at 5 req/s exceeded the 1-CPU PostgreSQL quota with a capped
generator. The k6 `JSON response` check expects `application/json`, so an RFC 9457
`application/problem+json` response fails it in addition to the status check.

### Saturated `normal` run at 25 req/s

`w943jpoq` repeated `normal` with the same SUT, limits and settle (VACUUM 4.2 s, no
autovacuum), at 25 req/s for 60 seconds with 30 VUs, at harness revision
`454875d`. It is retained in the same evidence file. k6 exited 99. Restoration
reconnected private-dev with unchanged aggregates and rating fingerprint
(21,036 Games, 37,529 Releases, 22 Ratings, 27 sync runs, none running),
no unexpected experimental rating, the experiment DB removed, unchanged
dependencies and readiness `UP`.

| Observation | Value |
|---|---:|
| Scheduled / completed requests | 1,500 / 702 (11.09/s) |
| Dropped iterations | 799 |
| HTTP 503 / 500 / timeouts / failed checks | 35 / 1 / 0 / 108 |
| Global p50 / p95 / p99 / max | 2,569 / 4,261 / 5,124 / 6,968 ms |
| Mean in-flight requests (reconstructed) | 28.3; all 30 VUs busy 60% of the time |
| PostgreSQL CPU / throttled periods | 63.7 s / 632 of 657 |
| PostgreSQL temp files / cancellations | 0 / 0 |
| Hikari active / pending max / timeouts | 10 of 10 / 20 / 39 |
| Application CPU / throttled periods | 25.3 s / 18 of 688 |
| Lenovo busy logical CPUs (of 8) / CPU PSI avg60 max | 1.61 / 56% |
| Clock backsteps / discontinuities | 0 / 0 |

Completions formed a flat plateau of 106–124 per 10-second interval throughout
the load, with p50 2.1–2.8 s, while drops stayed at 114–144 per interval. Every
503 is `CATALOGUE_READ_FAILED` caused by `SQLTransientConnectionException` and
lasted 3.0 s, the Hikari connection timeout. No statement was cancelled and no
temporary file was written. Errors were spread over release and detail cases.

- **Demonstrated:** the later five-minute reference below completed `normal` at
  12 req/s without errors or drops; the earlier 5 req/s run lasted 60 seconds.
- **Saturation observed:** at 25 offered req/s, PostgreSQL consumed its whole
  1-CPU quota and completed about 11 req/s with multi-second latency; Hikari
  queued up to 20 waiters and timed out 39 acquisitions. This is a saturated
  plateau under this generator configuration, not a sustainable capacity.
- **Not established:** the highest sustainable error-free rate. The later 12 req/s
  reference used the corrected SUT and a different window; the earlier saturated
  plateau is not an upper capacity bound.
  Drops are a closed-loop consequence: about 64 VUs (25 req/s × 2.55 s) would
  have been needed to sustain the arrivals, so the server never received
  25 req/s. Hikari 10 below 30 VUs moves the queue into the pool. Snapshot
  spacing is 10–17 s. Hikari counted 39 timeouts but 35 503 responses; that four
  readiness probes, which use the same pool, account for the rest is unverified.

**Transaction-deadline 500.** `remote-capacity-w943jpoq-652`
(`upcoming_approximate`) returned `INTERNAL_ERROR` after 6,968 ms with
`TransactionTimedOutException`. The 5 s read-transaction deadline starts after
connection acquisition. Spring checks it before each statement and throws when
nothing remains. The release, search, detail and featured catalogue adapters
classify `QueryTimeoutException` (the same deadline reached during a statement)
as `CatalogueReadException`, hence 503, but not `TransactionTimedOutException`
(a `TransactionException`, not a `DataAccessException`), which reaches the
generic 500 handler. The same exhausted budget is therefore reported as 503 or
500 depending on timing. Synchronization and rating adapters already treat it
as a timeout or transient failure. Since `0.29.1-SNAPSHOT` those four adapters
also classify `TransactionTimedOutException` (not `TransactionException` in
general) as `CatalogueReadException`, so the documented 503
`CATALOGUE_READ_FAILED` covers both cases; timeouts, Hikari, SQL and OpenAPI are
unchanged. `w943jpoq` ran `0.29.0-SNAPSHOT` and predates the correction.

### Five-minute serving reference

The owner executed `remote-capacity-ar4bukx5` on 2026-10-13 with the unchanged
`normal` profile, 12 req/s for 300 seconds and 30 VUs on `growth`.
The same [capacity evidence](remote-capacity-evidence.json) owns its exact
per-case distribution, SUT revision/digest, percentiles, resource deltas, clock
audits, restoration receipt and raw-file checksums. Raw evidence was recovered
from the original capacity worktree and remains ignored.

All 3,600 requests and 14,400 checks passed, with no errors, timeouts or dropped
iterations. Global p50/p95/p99 were 46/145/226 ms. PostgreSQL averaged about 46%
of its unchanged one-CPU quota over the 312-second server observation bracket;
this includes collection overhead beyond k6's 300 seconds. Hikari had no observed
pending connections or acquisition timeouts. There were no deadlocks, temporary
files, statement cancellations or observed ungranted locks. CPU throttling and
memory-limit events still occurred without OOM, so the average CPU use does not
prove absence of short pressure spikes.

The corrected `0.29.1-SNAPSHOT` image ran at deployed revision `34a8d8e`;
this differs from the earlier saturation pilot. Private data and the rating
fingerprint were unchanged after restoration, dependencies were unchanged,
readiness was `UP`, and the disposable DB was removed. This is an experimental
five-minute reference, not guaranteed production capacity, a long soak, or
background-interference evidence. No optimization follows from this slice.

## Prepared background scenarios; no measured overlap

The native launcher also supports the separate `authenticated` and `mixed`
modules; their capacity budgets remain a separate choice.

The original remote adapter already provided an internal fixture provider,
fixture credentials, a real application manual sync, localization checkpoint
reset, sampling, a disposable database, deployment lock and receipt-based
restoration. Its two-Game/four-Release input demonstrated protocol composition,
but no remote duration or HTTP overlap was measured. The current sequential
adapter needs roughly six provider API requests for that input; at the unchanged
default 3 calls/s its pacing component is about 1.7 seconds. That is an estimate,
not measured internal CPU/database time, and is too short to rely on with coarse
server sampling. The later reuse preparation below completed calibration only;
no remote serving/sync overlap has been measured.

### Bounded reuse work and evidence controls

`--scenario serving-sync-reuse` now uses a fixed 64-Game/128-Release fixture,
derived from the same provider test payloads, with two shared summaries and two
taxonomy texts. The fixture builder owns cardinality and payload digest. Public
`normal` cases, their distribution, infrastructure quotas, SQL, Hikari and JVM
settings are unchanged. Games are processed by the existing sequential application
flow, including provider mapping, reconciliation, real PostgreSQL transactions,
projection updates and cached Spanish publication. Online bounds remain the
existing 500-row provider page, one Game acquisition at a time and two releases
per fixture Game; fixture storage and ledger are finite.

Preparation seeds exactly four resolved translations in the OID/marker/schema
guarded experimental database, labelled `performance-offline-fixture`.
It blocks the application's translation endpoint on closed loopback, including
during preparation: no real model invocation is needed or permitted. It acquires
the fixture cohort, resets publication links with the existing guarded SQL,
measures one reuse calibration without k6, then resets the same links again for
the measured work. `background-preparation.json` retains warm-up, calibration
duration/result and immutable input digest. Calibration is a reference, not a
guarantee under simultaneous reads; it does not change the cohort automatically.

One release-window page, one logo lookup and two API calls per Game give about
130 API calls. At the unchanged default 3 calls/s, pacing alone suggests roughly
43 seconds, leaving a useful window in the planned 60-second run. Real mapping,
writes and contention add time; actual runtime and the configured pacing are
evidence, not assumed CPU pressure. No fixture sleeps, parallelism or extra
infrastructure are introduced. The fixture server still never forwards and
retains its original resource and 1,000-request ledger limits.

The measured manual sync is dispatched five seconds after native k6 process
startup. Process startup is only coordination; it is never taken as proof of
HTTP overlap. The host saves its start receipt before dispatch and records finish,
command duration, application `startedAt/completedAt`, outcome and counters,
durable last-run report, localization deltas and bounded provider ledger, including
on command failure. An immediate SSH clock exchange brackets host/controller
UTC offset before measurement; independent generator/host clock audits remain
required. k6 request intervals are reconstructed from actual completion timestamps
and `http_req_duration`, which excludes connection setup.

`evidence.json` and `interference-report.md` separate before/during/after and
uncertain boundary requests, with counts, errors and percentiles. They report
conservative overlap seconds/fraction, intersecting requests and server samples
starting within the real application sync window. The declared exploratory
sufficiency controls are at least **10 seconds, 50 fully contained requests and
two server samples**, stable clocks, successful complete cohort processing,
no observation failures, zero translated/failed localization deltas and at least
64 reused summaries. These are experiment usability criteria, not a product SLO.
Insufficient overlap or failed controls explicitly prohibit attributing degradation
to synchronization; even passed controls establish correlation only.

Existing before/after/periodic evidence retains PostgreSQL activity, locks,
transactions/buffers/temp/deadlock counters, JVM heap/GC/threads, Hikari, cgroup
CPU/throttling/memory/I/O and physical-host pressure. Samples are sequential and
coarse, can miss short waits, and add overhead. Localization meters are read by
bounded outcome tags. Full original application environment equality is now checked
during restoration; the protected runtime-file hash, scheduling, private counts
and rating fingerprint, dependency identities/limits and ownership cleanup remain
required. The report incorporates the restoration receipt only after recovery.
Sampling failure cannot skip application restoration; failed restoration produces
`recovery-required.json` and retains the owned host receipts.

### Aborted reuse attempt before observation

On 2026-10-13, `remote-capacity-s1o16z8w` completed `background_prepare`,
the fixture warm-up and reuse calibration (`SUCCEEDED`, 43.51 and 43.07 seconds),
and `settle` (35.31 seconds, including the required quiet window).
`start_observation` then failed with `Expected one running private-dev service`
before `before.json`, observation threads or native k6 execution. This attempt
provides preparation evidence only; it contributes no HTTP capacity, overlap or
interference result. Its ignored raw receipts remain separate from accepted runs.

The recorded and staged worker hashes match the reviewed source. `service()`
resolves the current container through Compose project/service labels on every
call; its error means the Docker listing returned a count other than one, but the
old message retained neither the service nor the count. `snapshot()` tolerates
unavailable Actuator meters, then requires PostgreSQL, container stats and cgroup
capture. Recreation waits for readiness, calibration and settling are sequential,
and observation threads start only after the initial snapshot. No harness
recreation race or stale container-ID reuse was identified at this boundary.

Restoration verified the original application environment and scheduling, unchanged
private data and dependency identities/configuration, database removal and readiness
`UP`. A subsequent read-only host inspection found unchanged dependency IDs, no
dependency restarts or OOM flags, and no retained Docker events in the failure
window. The experimental application container has been removed. A transient
Docker listing inconsistency is a hypothesis, not a confirmed root cause;
application exit/restart or temporary duplicate matches cannot be reconstructed
from the old error and these receipts.

Cardinality errors now report the service, container count and running/all lookup
scope; failed RPC replies include the action and durable experiment phase.
Diagnostics exclude container IDs, inspect payloads, environment values and raw
command output. Focused offline tests cover zero/multiple matches and initial
snapshot failure through RPC with restoration and no observation threads. The
single-container requirement, fail-fast behaviour and infrastructure are unchanged;
no automatic retry is added.

### Owner's next manual exploration

After review, merge and a separately authorized maintenance window, run exactly
one exploration; **no serving/sync overlap has yet been measured**:

```bash
# Use the exact deployed full SHA after a read-only plan; never infer it from checkout HEAD.
python3 tools/performance/remote.py capacity \
  --revision "$DEPLOYED_SHA" --approved-maintenance-window \
  --workload-profile normal --scenario serving-sync-reuse \
  --rate 10 --duration-seconds 60 --vus 30
```

The underlying growth seed remains 50,000 Games/200,000 Releases/500,000 Ratings;
preparation adds 64 acquired fixture Games and 128 Releases to that disposable
database. No real IGDB calls or inference occur. Shared PostgreSQL remains the
contention surface; private-dev data is isolated but normal private-dev use must
stop during the existing application maintenance window.

This fixed workload emphasizes refresh/reconciliation and publication from cache.
It does not represent unique-summary inference, new-catalogue import, large
release fan-out, historical backfill, provider latency/retries or all kinds of
locking. Most of the expected wall time may be application rate-limiter pacing;
duration alone cannot establish database pressure. A single exploratory run has
no matched serving-only control at the same rate/window, and the five-minute
12 req/s reference is not that control. If work outlasts k6, HTTP recovery after
sync is unobserved and must be reported as such; restored readiness and data are
separate recovery evidence.

`--scenario serving-sync-inference` retains the original two-Game/four-Release
real-helper preparation and remains unexecuted and outside this next experiment.
Neither remote scenario reproduces the local checksum-identical dump comparison.
Schedules and original provider/translation settings are restored after each
window. No SQL, pool, JVM or quota tuning is part of this preparation.
