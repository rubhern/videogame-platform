# Windows generator / Lenovo functional preparation

This slice of [#85](https://github.com/rubhern/videogame-platform/issues/85)
reuses the existing public, authenticated and mixed k6 modules, synthetic SQL and
fixture provider. It establishes remote functional evidence only. It adds no
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

## Prepared next phase; not executed

After owner review and approval of a new maintenance window, the first bounded
**public** capacity pilot can reuse this same adapter:

```bash
python3 tools/performance/remote.py capacity \
  --revision 8cb6071127b65c78fc74609502df4b4189b055d2 \
  --approved-maintenance-window --scenario serving-only \
  --rate 5 --duration-seconds 60
```

This is a single explicit rate, not an automatic escalation. It uses all twenty
existing growth read cases, four bounded VUs, the original limits and regenerated
`growth` data. It is a proposed pilot, not an approved run or saturation result.
Check the deployed SHA and run a read-only `plan` first; if the deployment changes,
record and pass its new exact SHA rather than silently accepting drift.

The native launcher also supports the separate `authenticated` and `mixed`
modules through configuration/user-file paths. The remote smoke orchestrates their
full identity lifecycle; longer authenticated capacity budgets remain an explicit
next-phase choice rather than promoting this serialized functional recipe.

`--scenario serving-sync-reuse` and `--scenario serving-sync-inference` are prepared
adapters for the existing fixture provider and reset SQL, with a bounded two-Game,
four-Release input and real loaded localizer. They use internal networking only,
fixture credentials instead of the protected real provider, and dispatch a single
manual sync after the Windows process starts. **Neither was executed in this
phase.** Before interpreting a later comparison, verify equal acquired state and
payload, actual overlap, reused/inferred outcome meters and helper evidence; the
remote adapter does not yet reproduce the local checksum-identical dump checkpoint
or local calibrated four-Game overlap evidence. Fixture latency never represents
real IGDB latency, throughput or cost. Schedules remain disabled during each future
measurement and restored afterward. No optimization or resource change is proposed.
