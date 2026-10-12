# Authenticated and mixed functional harness

## Scope and decision

This implemented slice of [#85](https://github.com/rubhern/videogame-platform/issues/85)
validates the existing authenticated MVP flows locally under WSL. It extends the
[k6 harness](../../../tools/performance/baseline.js), not the product, and does not
establish capacity, an SLO, stress/recovery or soak evidence. Clock backsteps are
retained independently of functional checks. No optimization, exporter, production
instrumentation, CI gate or releasable-artefact version increment is introduced.

The [OpenAPI contract](../../architecture/api/openapi.yaml) owns session, personal
rating search/sort and conditional-command semantics. The
[solution architecture](../../architecture/mvp-solution-architecture.md) owns the
BFF and identity boundary. The executable owners for this experiment are
[authenticated.py](../../../tools/performance/authenticated.py),
[authenticated.js](../../../tools/performance/authenticated.js),
[mixed.js](../../../tools/performance/mixed.js) and the existing
[dataset generator](../../../tools/performance/dataset.sh).

## Method and isolation

The existing application, Keycloak and PostgreSQL topology is reused. The
`authenticated` profile supplies a small synthetic catalogue with no initial ratings;
this is a functional fixture, not a growth forecast. The application connects only
to the verified disposable performance database. The existing Keycloak service
connects to its disposable identity database in that same PostgreSQL container.
Persistent catalogue and identity databases remain separate and untouched.

Three deterministic synthetic usernames and disjoint sets of three eligible Games
are assigned to three scenarios, each with exactly one VU. Assignment uses a fixed
scenario environment slot, independent of k6's global VU allocation. Passwords are
random per run and supplied through a temporary mode-0700 directory/mode-0600 file;
passwords, session cookies, CSRF material and authorization codes are absent from
retained evidence. Subject IDs are created by real Keycloak; reproducibility concerns
the recipe and isolated histories, not reuse of an old principal.

Provisioning reuses the management-only `KeycloakAdmin` seam already exercised by
[scripts/test-private-dev-oidc-provisioning-keycloak.py](../../../scripts/test-private-dev-oidc-provisioning-keycloak.py).
Its admin token remains in the controller and never enters the product workload.
The VUs visit `/auth/login/keycloak`, submit the hosted Keycloak HTML form, follow
the authorization-code callback through the BFF and retain only opaque cookies.
They never call an OAuth token endpoint or send a product bearer token. Session
rotation and HttpOnly/SameSite=Lax are checked. A second iteration reuses the cookie
jar with `noCookiesReset`, obtains current CSRF material from `/session`, and checks
that the session response contains only the product's minimal fields.

Local k6 DNS/port mappings preserve the product's real localhost Host headers,
redirect URI, issuer and cookie domains while reaching the existing containers.
This uses [k6's documented hosts option](https://grafana.com/docs/k6/latest/using-k6/k6-options/reference/#hosts).
It changes network routing only. Remote TLS origins and the approved secure cookie
name can be supplied in the same workload configuration without those local mappings.
No session or authentication protection is disabled. Runtime log output is suppressed
so even a failed HTTP callback cannot persist its authorization-code URL; failures
remain visible through status/error metrics, checks and request-budget thresholds.

Each user prepares two personal ratings through conditional API creation. Each of
two measured journeys reads all three supported sorts, searches canonical titles
and approved aliases, checks another user's seeded rating is absent in its own scope,
and creates, reads, updates, reads and deletes its own third rating. Reads verify
values and returned validators; mutation results verify coherent available aggregate
context. A missing-CSRF creation attempt must fail with the existing stable error.
There is no personal platform/region/genre filter in the current contract; none is
invented. All list responses remain one bounded page of at most three expected items.

Mixed composition adds a separate anonymous VU cycling over the existing five public
cases. The original public executor remains unchanged; authenticated-only execution
has its own module/options. Each VU pauses one second after requests. This controller
has a fixed small budget, no increasing-load or sustained-load CLI switches.

## Preparation, recovery and reproduction

Start the supported local application from current main using the
[local setup procedure](../../development/local-setup.md). Disable scheduled
synchronization and stop the optional localizer. The controller refuses remote Docker
engines, an existing performance database and an unexpected persistent application
boundary. The existing disposable-container/image/anonymous-volume and SQL schema
checksum controls remain mandatory.

```bash
python3 tools/performance/authenticated.py run \
  --runtime-checkout /path/to/local/checkout-with-existing-env-files --workload mixed
```

`--workload authenticated` selects the same authenticated scenarios without public
traffic. `--workload mixed` is the default and was functionally executed here.

Raw evidence goes to ignored `tools/performance/results/authenticated-*`: manifest,
workload, inspected k6 options, timestamped metrics, native summary, case/scenario
results, paired wall/monotonic samples, periodic existing Actuator/Docker/cgroup/SQL
observations and restoration receipts. Login redirects and raw headers/bodies are
never exported. Functional failures fail the run; clock-invalid runs retain
`capacityInterpretationAllowed: false` without failing otherwise correct flows.

Normal completion deletes every synthetic rating through the API and terminates
BFF sessions with CSRF. The controller verifies zero remaining ratings and deletes
its newly created accounts with an ID/email ownership check. Finally it restores
application and Keycloak connections to their original persistent databases, checks
readiness, compares catalogue counts and the aggregate fingerprint of persistent
rating rows, then removes only the verified disposable database and anonymous volume.
The temporary credential directory is removed even after failure. If interruption
prevents normal completion, disposal of the isolated identity/application databases
removes partial state. Recovery verifies the recorded disposable container ID before mutation, so an old
receipt cannot remove a newer experiment. It can be resumed idempotently:

```bash
python3 tools/performance/authenticated.py restore \
  tools/performance/results/authenticated-EXACT-RUN
```

## Evidence and limitations

The corrected run `authenticated-gv7utc0a` passed 152 requests / 545 checks, zero
unexpected HTTP failures/timeouts/dropped iterations, and all 26 planned iterations.
Each authenticated slot made 44 requests / 155 checks, one login and one session
reuse, two workload creates, two updates and two deletes. Twenty public requests
(four each for recent/upcoming/featured/search/detail) passed 80 checks concurrently.

| Request group | Calls |
|---|---:|
| Keycloak/BFF login steps | 15 |
| Session discovery and anonymous post-logout checks | 9 |
| Mis puntuaciones: default, title/value sorts, title/alias search, post-update/delete | 42 |
| Personal single-rating reads: peer/own absence, created, updated, deleted | 30 |
| Create/update/delete, including preparation/restoration | 30 |
| Missing-CSRF rejection | 3 |
| BFF logout | 3 |
| Public cases | 20 |

Six seed ratings were created/restored in addition to six workload creates, six
updates and six deletes. Status checks include expected 201, 200, 302, 204, 403 and
404 responses; expected rejection/absence is not an unexpected HTTP error. The
mixed composition is 132 authenticated/lifecycle calls (86.84%) and 20 anonymous
public calls (13.16%), by count, not a clock-derived throughput distribution.

Native global p50/p95/p99 were 15.30 / 254.99 / 1,165.86 ms. `clockAudit` retained
three HTTP timestamp backsteps (0.170, 0.271 and 7.539 seconds), one microsecond-scale
concurrent reordering and twelve paired wall/monotonic discontinuities across 196
samples, including negative wall deltas of about 8.42 seconds. These are kept as
observations; no throughput or capacity interpretation is allowed. The failed
assignment diagnostic `authenticated-wfjbdx7h` retained 108 requests / 390 passing
checks, but only two sessions/reuses and a failing k6 exit; its one HTTP backstep
and thirteen paired discontinuities also remain in the machine evidence.

Seven periodic existing-telemetry snapshots plus before/after observations retained
heap 102.0–154.2 MiB, threads 55–62, three additional GC pauses / 111 ms, Hikari
sampled active/pending 0 / 0 with maximum pool 10 and timeout delta 0. PostgreSQL
recorded 434 additional commits, 8 rollbacks, 9 block reads, 23,721 block hits and
zero new temp files/bytes/deadlocks; sampled ungranted locks were zero. Sampled
zero-valued gauges do not establish absence of transient connection/lock pressure.
Existing container CPU/memory and cgroup counters are retained without clock-invalid
CPU/rate attribution. The native server counter after the run was 146: six of the
152 HTTP requests went to Keycloak. The server meter was not registered before
product traffic; raw before/after observations preserve that availability limit.

The application and Keycloak were restored, readiness was UP, persistent counts
remained 20,241 Games / 36,090 Releases / 7 Ratings and persistent rating-row
fingerprints matched. Zero experiment ratings and explicit deletion of all three
synthetic accounts were verified before removal of the disposable database/volume.

The compact results and raw-run identifiers are recorded in
[authenticated-evidence.json](authenticated-evidence.json). The failed diagnostic
run remains visible beside the corrected proof; it is not promoted to success.
The local persistent Keycloak administrator password was rejected before mutation,
so the proof uses fresh disposable identity state and preserves existing accounts
and credentials.

HTTP percentiles include login and cold-path work and are observations only. Existing
JVM/GC/Hikari, PostgreSQL counters/grouped activity/locks and container resource
signals are diagnostic samples, not exhaustive wait histories or physical Windows
host pressure. No expiry/refresh longevity, browser rendering/accessibility, external
provider timing, synchronization interference or capacity conclusion follows from
this short HTTP proof. BFF tokens remain server-side; this workload does not inspect
them or claim to validate a long refresh cycle.

For Lenovo capacity testing, use an external generator with stable audited clocks,
reachable registered BFF/Keycloak TLS origins, a protected credential handoff and
explicit generator/controller start coordination. Provision an isolated catalogue
and independently owned synthetic accounts on the target with a recoverable cleanup
boundary before reuse of the same scenarios. Define the intended dataset/concurrency,
session lifecycle, case mix and success/saturation criteria separately; add physical
host resource capture and longer-window clock audits. Scenario origins, cookie name, routing mappings, iteration budgets, pause and maximum
duration are workload configuration; the local controller deliberately
retains its local-engine guards and fixed short budget. Remote launch/coordination and
capacity execution remain future #85 work, not an implemented claim.

Focused checks passed: six controller safety/evidence regressions, fourteen existing
disposable-boundary controls, Python syntax, independent pinned-k6 parsing of public/
authenticated/mixed modules, final default options matching the executed proof,
report regeneration, documentation links and diff whitespace. Product suites and
mandatory CI were not added for this tooling-only change. Owner review/publication
and the broader acceptance of #85 remain pending.
