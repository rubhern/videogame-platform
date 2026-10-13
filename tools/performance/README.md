# Remote performance harness operations

This slice of [#85](https://github.com/rubhern/videogame-platform/issues/85)
reuses the existing public, authenticated and mixed k6 modules, synthetic SQL and
fixture provider. It establishes functional, public-load, interference and bounded
stress/recovery evidence, without a production-capacity guarantee. It adds no
product code, migration, contract, exporter, mandatory CI gate or releasable
artefact version increment. This record owns the experiment method and evidence;
the [private-dev procedure](../../deploy/private-dev/README.md#owner-connection-from-wsl)
owns SSH access and deployment operations.

## Implemented adapter and connection

The controller runs in WSL; **k6 runs as a native Windows process**, outside WSL
and Docker Desktop. Windows reaches the existing private Tailscale HTTPS origin
and real Keycloak issuer, using their registered hostnames, ports and certificates.
No DNS override, insecure TLS option, bearer-token workload or authentication
bypass is introduced. Actuator, PostgreSQL and administrative Keycloak access
remain internal and are observed through the owner's SSH alias.

[remote.py](../../tools/performance/remote.py) owns orchestration,
[remote_worker.py](../../tools/performance/remote_worker.py) owns the host
adapter, and [windows-k6.ps1](../../tools/performance/windows-k6.ps1) owns the
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
python3 tools/performance/remote.py plan --revision "$DEPLOYED_SHA"
python3 tools/performance/remote.py probe --revision "$DEPLOYED_SHA"
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
Every new window needs explicit owner authorization; the CLI acknowledgement alone does not grant permission.

```bash
# Only within an explicitly approved maintenance window:
python3 tools/performance/remote.py smoke \
  --revision "$DEPLOYED_SHA" \
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
per user reuse the [authenticated journey](../../docs/research/performance-baseline/authenticated-harness.md). An existing
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


## Capacity and completion scenarios

Results and interpretation belong to the [final report](../../docs/research/performance-baseline/remote-harness.md).
Run `plan` first and use the full deployed SHA, verified against OCI and Actuator.
The controller stages current harness bytes; changing the harness requires no product deployment.
These examples are procedures, not authorization to run:

```bash
python3 tools/performance/remote.py capacity --revision "$DEPLOYED_SHA" \
  --approved-maintenance-window --scenario serving-only \
  --workload-profile normal --rate 10 --duration-seconds 60 --vus 30
python3 tools/performance/closure.py inference --revision "$DEPLOYED_SHA" --approved-maintenance-window
python3 tools/performance/closure.py mixed --revision "$DEPLOYED_SHA" --approved-maintenance-window
python3 tools/performance/closure.py stress --revision "$DEPLOYED_SHA" --approved-maintenance-window
python3 tools/performance/closure.py provider --revision "$DEPLOYED_SHA" --approved-maintenance-window
```

`capacity` also supports `serving-sync-reuse` and `serving-sync-inference`.
`closure.py` owns the predeclared bounded matrix: 60-second levels at 10/15/20/25 req/s,
30 VUs, stopping at the first degradation (>=1% errors/drops, <95% delivery, p95 >1 s).
Recovery uses the same instance at 5 req/s for 60 seconds, requiring no errors/drops,
stable clocks and p95 <=max(500 ms, twice baseline). These are experimental gates, not SLOs.
The watchdog stops the owned generator for OOM, readiness loss or unavailable/stale
(>45 s) observations. Critical safety failure prohibits further load.
`settle` performs VACUUM (ANALYZE) only on the guarded DB, requiring 30 seconds without
autovacuum/vacuum progress, with a 300-second ceiling.

Snapshots retain existing JVM/GC/Hikari/HTTP meters, PostgreSQL counters/activity/locks,
cgroup CPU/memory/I/O, physical host pressure and independent Windows/Lenovo clock audits.
Approximately five-second samples include collection overhead; short events can be missed.
Management ports stay internal. Allowlisted errors omit secrets, URLs and SQL text.
Provider calibration permits only official endpoints, three one-row reads at one-second
spacing and no retries; it does not measure a real full synchronization.

The service resolver excludes only the recorded auxiliary ID after ownership and
automatic-removal verification, then requires exactly one real service and checks
preflight dependency identity. Image-inherited Compose labels cannot make that
known helper a service; real duplicates and unknown/replaced containers still fail.
Background POSTs pin the validated experimental app; restoration makes the original
app available before refusing cleanup when background work cannot drain.

## Evidence and offline validation

Raw runs, ownership receipts, policy, manifests, clock samples and checkpoints remain
in ignored `tools/performance/results/`. Retain them until recovery and publication
are verified. Never publish cookies, protected origins, passwords or raw provider data.
Frozen research JSON keeps allowlisted summaries and script/raw hashes. Recorded
script hashes identify measured bytes, which can differ from later safety fixes.

```bash
python3 -m unittest discover -s tools/performance -p 'test_*.py'
bash scripts/validate-docs.sh
```

Local-only dataset, authenticated and checkpoint-comparison procedures remain in
the linked [research method notes](../../docs/research/performance-baseline/README.md).
