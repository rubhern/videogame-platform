# Operations runbook

This runbook answers the operational questions of the private learning MVP with
procedures that have already been executed in the environment they target. It links
to the executable owner of each command instead of copying it; the
[platform and delivery design](../architecture/deployment/mvp-platform-and-delivery.md)
owns the policy behind every section and the
[private-dev README](../../deploy/private-dev/README.md) owns the operator commands
for `vgpdev`.

Evidence standard: a procedure appears here as **proven** only when the owner ran it
and recorded the outcome in the linked issue or pull request. Anything else is marked
**not exercised**. Repository validation (for example
`scripts/test-private-dev-backup-recovery.sh`) proves logic, not host behaviour.

The accepted MVP is private, single-host and zero-cost. Nothing here claims public production,
high availability or automatic recovery. Retained private-dev metrics and aggregated logs are
disposable operational history.

The [public target](../architecture/deployment/mvp-platform-and-delivery.md#public-production-target)
and [separate purchase/exposure gates](delivery-lifecycle.md#public-production-gates)
follow the accepted ADR-0025 design; the gates remain open and no production
procedure has been exercised on a real host. The [production recovery candidate](../../deploy/public-production/README.md#paired-backup-and-recovery-candidate)
owns prepared commands and the synthetic host-loss exercise, with its real-host limits. Record
real-VPS evidence here only when those procedures are executed; private-dev evidence
does not certify production networking, capacity, email or recovery.

## Environment responsibilities

| Environment | Where | Who operates it | Data | Runbook sections |
|---|---|---|---|---|
| `local` | Ubuntu 24.04 on WSL2 | Each developer | Disposable, generated secrets | Local workstation |
| `test` | GitHub Actions | CI only | Generated fixtures | Not operated by hand; see [continuous integration](continuous-integration.md) |
| `dev` | `vgpdev`, owner tailnet only | Owner, over SSH through Tailscale | Persistent learning data: ratings, identity, curation | Everything from *Diagnosing private dev* onward |

Commands that mutate or deploy the live `dev` stack run only on `vgpdev`; the
deployment script refuses any other host, and a local reset never targets `dev`.
Isolated or off-host operations (backup integrity verification, the isolated restore
rehearsal) may run elsewhere as their canonical procedure describes. The local
wrapper refuses any Compose project other than its own.

## Local workstation

Owner: [local setup](local-setup.md) and `scripts/local-dependencies.sh`.

| Question | Proven procedure |
|---|---|
| Are prerequisites right? | `bash scripts/validate-prerequisites.sh` |
| Start / verify / stop dependencies | `bash scripts/local-dependencies.sh up`, `verify`, `status`, `logs`, `down` |
| Run the complete packaged application | `bash scripts/local-dependencies.sh application` |
| Run a migrated backend from source | [Database migrations](database-migrations.md#validate) |
| Inspect health, info, metrics, last sync | [Observability](observability.md#local-inspection) |
| Trigger a local catalogue synchronization | **Solicitar sincronización** at `/administracion/catalogo` with an operator session ([catalogue synchronization](#catalogue-synchronization)); fallback: [Backend README](../../backend/README.md#persistence-and-observability) (`POST /actuator/cataloguesync` on `127.0.0.1:8081`) |

Destructive: `bash scripts/local-dependencies.sh reset`.

- Target: only the fixed local Compose project's containers, network and PostgreSQL
  volume. Repository files, `.env` files, images and unrelated volumes are untouched.
- Precondition: confirm the volume holds disposable project-local data. A local
  database that already applied a migration you still need is not disposable.
- Recovery: there is none; the data is gone. Re-run `up` and, if needed, the seed
  path in [database migrations](database-migrations.md).

Common local symptom: every login returns to the game as "not completed". Cause and
fix are documented in [local setup](local-setup.md#disposable-reset) (the stored
Keycloak client secret no longer matches `.env`; `verify` reports it).

## Public-production manual deployment

**Prepared for local synthetic rehearsal; not exercised on a real host.** The
[production operator procedure](../../deploy/public-production/README.md#manual-deployment-procedure)
owns the command, reviewed inputs, refusal/retry behaviour and sanitized receipts.
It uses the existing production Compose runtime and trusted immutable publications.
The command blocks real-host operation unconditionally while backup/recovery and
release prerequisites remain unsatisfied. Its disposable transition exercise preserves
synthetic database state between attempts and removes that state at final teardown.

Review the attempt's outcome, failure phase, source/image/configuration identities,
previous installed version and migration version before retry. A failed migration
prevents activation; failed readiness/smoke stops the candidate. Do not restart the old
image against an unassessed schema, revert Flyway, or force past incomplete state.
Follow the linked schema-compatibility/refusal procedure; unresolved recovery requires
an assessed forward fix or separately approved recovery. A rehearsal receipt is local
logic/runtime evidence only. Record real-host acceptance here after a later approved
host procedure has actually been exercised.

## Diagnosing private dev

Owner: [private-dev README](../../deploy/private-dev/README.md) and
`scripts/validate-private-dev-runtime.sh`. The commands in this section inspect the
live stack, so run them on `vgpdev`, from the checkout, with the protected runtime
file at `/etc/videogame-platform/dev/runtime.env`.

Proven on `vgpdev` (#43, #36, #44, #45):

- Static validation of the reviewed topology without touching services:
  `bash scripts/validate-private-dev-runtime.sh --env-file <runtime.env>`.
- The original bounded telemetry receipt check with a disposable collector. The
  extended metrics-stack smoke now has a separate evidence boundary below.
- Stack state, logs and resource use of the `dev` Compose project with
  `docker compose --env-file <runtime.env> --file deploy/private-dev/compose.yaml ps`,
  `logs <service>` and `docker stats --no-stream`. Add `--profile application` when
  the question is about the `application` service.
- Edge routes: `tailscale serve status` (two HTTPS routes, 443 and 8443) and
  `tailscale funnel status` (must report nothing enabled).
- Restart and full-reboot persistence of PostgreSQL, Keycloak and the collector,
  with database ownership preserved (#43 evidence).

Not exercised on `vgpdev`: `validate-private-dev-runtime.sh --env-file <runtime.env>
--live`. The mode exists and is validated in the repository; treat its first host run
as new evidence to record.

Interpretation:

- `GET /api/v1/releases` answering `CATALOGUE_NOT_READY` is an approved state of a
  database without any publication, not an incident. Deployment health never depends
  on IGDB.
- Readiness covers only PostgreSQL and the catalogue store. IGDB, the cover CDN,
  catalogue-localizer and the collector being down never make the application unready.
- The application management port (`8081`), PostgreSQL, OTLP and Keycloak management
  have no host listener by design. A "connection refused" from the host to those ports
  is correct.

## Metrics storage and dashboards

Owner: [Metrics dashboards](../../deploy/private-dev/README.md#metrics-dashboards).
The optional local startup is owned by
[local setup](local-setup.md#local-metrics-and-logs); local lifecycle tests prove
argument handling and secret/reset boundaries. The disposable local smoke
(`bash scripts/test-local-observability.sh --smoke`, requiring free local ports)
also proved loopback OTLP receipt, Prometheus queries, authenticated Grafana
provisioning and actual container bounds with the pinned images on 2026-09-27.
Its synthetic probe and empty application panels are not real application or
private-host evidence.
The owner-running local application has also supplied real OTLP, bounded product
traffic and manual synchronization evidence recorded on #158. Its functional review
distinguishes exact process totals, retained samples and the durable run report as
explained in [observability](observability.md#interpretation-and-evidence-limits).
That local evidence does not satisfy private-host acceptance.
[Observability](observability.md#private-dev-dashboards) explains the three views and
the limits of their product-learning proxies.

**Not yet exercised on `vgpdev` for #158.** The extended disposable smoke validates
repository configuration and data flow only. Host acceptance must record real OTLP
arrival, authenticated queries over the owner SSH tunnel, one representative panel
per dashboard category, retained samples after Prometheus/Grafana recreation, and
idle/normal-use CPU/memory/PID/disk measurements. Readiness and product reads must
continue during a bounded metrics-stack outage. Record outcomes in
[#158](https://github.com/rubhern/videogame-platform/issues/158), never infer them from
container limits or synthetic samples.

If a dashboard is empty, first check its time range and whether that operation has
occurred since application startup. Then inspect Collector receipt, Prometheus
scrape health and Grafana datasource health using the linked validator. A scrape
failure isolates the handoff; absent application series with a healthy scrape can
mean an OTLP export failure. Never recover telemetry by exposing Actuator or changing
product readiness. Follow the linked procedure for recreation and recovery without
touching PostgreSQL volumes.

## Private-dev alerts

Owner: [alert semantics and limits](observability.md#private-dev-alerts) and
[provisioning/rollout procedure](../../deploy/private-dev/README.md#private-dev-alerts).
**Live alert rollout/evaluation is not exercised.** Isolated validation proves rule
logic and provisioned behaviour only; #269 records controlled lifecycle evidence and
the earlier read-only host inventory. Telegram is prepared but disabled by default;
no real message delivery or independent detection of host/Grafana failure is proven.

| Alert | First owner action |
|---|---|
| Unexpected server errors | Open Application / HTTP and Platform logs for contributing error codes and time range; check private readiness and correlate deployment changes. Expected personal-rating absence and empty-catalogue responses are excluded. |
| Sustained product-route latency | Open Application / HTTP for the route/method, then Runtime / Resources for Hikari and bounded database diagnostics; inspect expensive search patterns without logging raw search input. |
| Hikari connection acquisition pressure (Telegram: *Database connection pool pressure*) | Inspect pending, acquisition timeouts and HTTP 503s in Runtime / Resources; examine database activity/locks and query durations. Reduce an optional operator workload if safe; do not tune pool/quota/SQL without evidence and a separate decision. |
| Application telemetry unavailable | Check both scrape targets and advancing application uptime in Runtime / Resources, then private readiness/product reads. Healthy scrapes with frozen uptime suggest broken export or an unavailable application; failed scrapes locate a collection problem. A Prometheus query error identifies unavailable telemetry, not proven application downtime. |
| Catalogue synchronization needs attention | Open Catalogue Synchronization's retained run reports and the run's `finished` log line; its `failures=` tally names each stage and reason. Inspect credentials, provider responses or the datastore as the reasons indicate, and retry only a justified bounded interval. Confirm success in the durable ledger; automatic alert resolution only expires recent failure evidence. The [failure policy](#when-a-synchronization-needs-an-operator) says what is not an incident. |

A single ordinary error, short queue or restart, idle traffic, normal sync, skip or
lack of sync is intentionally quiet. During planned maintenance, allow the pending
period to absorb short interruptions; a longer interruption remains visible and
must be interpreted with maintenance context. Follow [incident handling](#incident-handling)
for persistent degradation. If Grafana is unavailable, use existing SSH/container
inspection; this alert set has no independent watchdog.

## Telegram delivery

Follow the [single setup/activation procedure](../../deploy/private-dev/README.md#telegram-setup-and-delivery).
In Grafana, inspect Alerting's rule instances, notification policies, contact point
and notification errors. A firing rule does not prove message delivery. For failed
HTTPS sends, inspect the bounded Grafana info logs for redacted errors, token revocation,
chat access, outbound DNS/TLS/connectivity or Telegram rate limiting. Do not print the
rendered contact settings, enable notifier debug logs, or paste token-bearing URLs.
When delivery is disabled, a brief closed-loopback HTTPS error from an old persisted
receiver is expected while policy reconciliation finishes; the wrapper prevents a send.
A timeout is ambiguous; do not assume safe duplicate-free manual replay. Native
integration retries are limited as described in [observability](observability.md#notification-boundary).

Operational resolved messages mean the predicate cleared. Sync resolved messages mean
the failure window closed; inspect the durable report for a subsequent successful run.
Ordinary restarts fit within the operational pending windows. Planned longer work
should have an owner-managed Grafana silence, without changing provisioned rules.
Telegram cannot notify when this same host or Grafana is unavailable.

## Production monitoring

**Prepared candidate, not a deployed operation.** Provisioning and secret/operator
inputs are in the [production runtime guide](../../deploy/public-production/README.md#production-observability-candidate);
[observability](observability.md#production-alert-interpretation-and-calibration)
owns threshold meaning, unknown-data behaviour and #276 calibration.

On a warning, compare traffic trend with host/container headroom and Hikari/database
pressure. Pause acquisition/translation and deployment overlap before raising limits.
For disk warnings, inspect WAL, telemetry, image generations and backup scratch; stop
new jobs before crossing the approved reserve. Never delete identity/ratings or the
last verified recovery pair to silence a warning. A critical CPU/memory, OOM/restart,
error or identity signal needs private readiness checks and bounded diagnostics;
use the existing edge/identity-maintenance controls if integrity or recovery is uncertain.
Aggregate identity events warrant checking relay allowance and narrowing exposure;
never add addresses, accounts or tokens to notification text.

For missing telemetry, inspect the Collector, scrape limits/targets, textfile timer,
Caddy safe encoder schema and Grafana datasource health. Compare the last host/export
heartbeat; an absent series is not healthy zero traffic. A stopped telemetry component
must leave product reads and readiness available. For missing/failed/stale backup proof,
check the source backup attempt, independent pull, protected isolation review and the
matching full restore/deletion-replay receipt. A local dump or checksum-only receipt
must never reset the verified off-host recovery-point age.

For TLS warnings, inspect both certificate chains and Caddy renewal diagnostics/state
privately. Verify persistence and the actual ACME path; never erase certificate state
as an automatic fix. A local probe is insufficient evidence of external availability.
When an alert closes, confirm the actual service and next successful operation; a sync
window expiring does not mean a later sync succeeded. Keep notification grouping and
repeat timing; do not send raw logs or identifiers to Telegram. Test delivery and recovery
through the owner channel before exposure, then verify the independent-device cadence
for host loss and stale backup checks under #276.

## Product growth

Open **Gameómetro · Growth** for observed registrations, verification actions and
rating commands. Check source/target health and the activation date before treating
an empty or zero value as no activity. The optional Keycloak overlay needs a separately
reviewed rollout and a rebuilt private image with event metrics compiled before
optimized startup. Runtime enablement alone caused the earlier rolled-back startup
failure; keep the base rollback state until the corrected targeted rollout is approved.
The [operator procedure](../../deploy/private-dev/README.md#separable-product-growth-rollout)
owns preparation, old-image recovery and post-rollout acknowledgement. Do not change
public identity/verification policy for these metrics.
No growth activity while idle is normal, and an email-verification event is not a new
registration. Current accounts, distinct verified accounts, organic totals and lifetime
milestones are unsupported; the dashboard states those limits.

Registration notices are opt-in window observations. No closing message is sent when
the window ends; several registrations can share one notice, and sparse/restarted
collection can miss one. The displayed count is an estimate, not an exact account
total. Live Telegram remains pending owner credentials and
separate send approval; a passing TLS mock is not delivery evidence. Ratings counters
only observe committed
commands; direct SQL fixtures are excluded, synthetic API callers are included. Use
the current active SQL total for inventory, never counter subtraction or a rolling
window for a lifetime milestone. The [durable follow-up proposal](observability.md#proposed-durable-notification-follow-up)
owns the remaining business-delivery semantics. No milestone notification is enabled.

## Log aggregation and search

Owner: [Log aggregation and exploration](../../deploy/private-dev/README.md#log-aggregation-and-exploration)
for rollout, disk checks, persistence, outage, rollback and narrowly targeted
history recovery; [observability](observability.md#private-dev-log-exploration) for
Explore queries and interpretation.

**Not exercised on `vgpdev` for #159.** The repository adds a disposable logging
smoke with known ECS events, exact label checks, authenticated Grafana queries and
recreation/outage checks. Passing it does not prove private-host delivery, security,
capacity or eventual retention deletion. Record host outcomes in
[#159](https://github.com/rubhern/videogame-platform/issues/159) before marking those
procedures proven; do not infer them from the existing metrics or deployment evidence.

Host acceptance remains: reviewed application recreation with its current immutable
digest, real correlated events searchable through the owner tunnel, bounded local
cache inspection, no public IPv4/IPv6 receiver/API, Loki history surviving recreation,
product readiness/reads during logging outage, and representative idle/load/disk
measurements plus eventual compactor deletion. Follow the linked procedure for disk
pressure or corrupt disposable history. Query absence alone is insufficient evidence
of physical deletion, and collector/UDP availability is insufficient evidence of
lossless collection. No audit-log, alerting, AI diagnostic or retained-trace capability
is claimed.

## Deploying an immutable digest

Owner: [Owner-triggered deployment](../../deploy/private-dev/README.md#owner-triggered-deployment)
and `deploy/private-dev/bin/deploy-private-dev`.

Proven: the command completed on `vgpdev` with a `success` evidence record for a
trusted `main` revision, and that deployment served the MVP acceptance in #45. Its
earlier runs recorded real failures at the smoke phase; each was diagnosed from the
smoke output and fixed forward (#144–#149, #170).

Before invoking it:

1. Choose a `main` revision whose required checks, image publication and scan/SBOM
   evidence you have reviewed. Take the digest from that publication; never from a tag.
2. Confirm the dependency stack, including the required catalogue-localizer, is healthy (`ps` above) and that nobody else is
   deploying. The command holds one host lock and refuses concurrency.
3. Know the current Flyway version and whether the new revision adds migrations. A
   migration failure leaves the previous application running; a smoke failure records
   `failure` even though the candidate may still answer.

The command records one JSON file per deployment under
`/var/lib/videogame-platform/dev/deployment-evidence/` and prints its path. That
file is the deployment record; do not summarize it elsewhere except as a link or the
outcome/phase pair in an issue comment.

A deployment is never a named release or product acceptance. The
[delivery lifecycle](delivery-lifecycle.md) owns release and acceptance records.

**Original path exercised on `vgpdev` for #160:** the
[owner-approved Actions promotion](../../deploy/private-dev/README.md#owner-approved-actions-promotion)
has a successful retained host receipt (`outcome=success`, `phase=complete`) in
[the original promotion run](https://github.com/rubhern/videogame-platform/actions/runs/37339424266).
That proves the previous promotion path, including its technical smoke. **Acceptance
of the refined single-dispatch flow remains pending**. Local doubles prove the new
discovery/refusal logic; they do not prove the changed environment configuration or
authorize a host change or deployment.

Before marking the refined flow proven, record the owner's implementation/host approval,
the saved reviewer-free, main-only environment policy with bypass disabled, exact
dispatch-SHA/run/attempt/digest derivation and tailnet restrictions, forced-command/forwarding denial,
pinned host-key verification, refusal of stale/mismatched CI and runtime evidence,
and one owner-dispatched promotion without input fields or a second approval, with
successful original host evidence and matching browser startup version/revision.
Check that dependency container identities
and configurations were not recreated by application promotion. Exercise lock contention
and the applicable failure/recovery path through the existing procedures, without
reverting an applied migration. Record outcomes in
the current delivery issue. #160 is closed; the localization lifecycle and its new
host acceptance are tracked in [#257](https://github.com/rubhern/videogame-platform/issues/257).
Its closure does not supply new runtime evidence. A failed or interrupted workflow requires inspecting
protected host logs/evidence before another owner action, even if the application answers.

## Catalogue synchronization

Owner: [Backend README](../../backend/README.md) for the command semantics,
[ADR-0017](../decisions/0017-discover-catalogue-members-automatically-from-igdb.md)
for reconciliation,
[`application.yaml`](../../backend/src/main/resources/application.yaml) for executable
policy defaults and bounds, `backend/.env.example` for local configuration and
[`runtime.env.example`](../../deploy/private-dev/runtime.env.example) for private-dev
opt-in/overrides. These are acquisition policies, not product/business invariants.

Post-MVP (#155, implemented and accepted in private dev): opt-in recurring near-term
and upcoming policies invoke the same UC-009 application path as the exceptional
`POST /actuator/cataloguesync`. `CATALOGUE_SYNC_SCHEDULING_ENABLED` defaults off,
independently of credentials. Each policy accepts a six-field Spring cron; `-`
disables only that policy. Non-negative past/future day offsets derive an inclusive
window from one evaluation of the application clock in `Europe/Madrid`; neither the
host timezone nor the UTC provider date-query boundary changes this evaluation.
Configuration changes take effect on application recreation. Invalid cron/windows
are configuration errors detected at startup.

Initial policy rationale: daily off-peak near-term refresh covers the weeks around today
that release exploration opens on. Weekly upcoming refresh covers
roughly six months ahead, keeping wider acquisition less frequent. The default start
times are staggered to reduce contention; the precise crons/offsets stay in
`application.yaml` and can be tuned after observing run duration and provider volume.
Their overlap is deliberate and uses stable-reference idempotence; distinct policy windows
are not merged. Repeated pending occurrences of the same policy coalesce into one slot,
and each successful run refreshes the coverage of its own window.

The widest future window among the policies not disabled with `-` (the upcoming policy's
180 days by default) is also the configured forward acquisition horizon that bounds
TOPómetro's monthly navigation ([UC-010](../architecture/application/mvp-use-cases.md)):
months stop at the last month lying wholly inside it. It applies whether or not
`CATALOGUE_SYNC_SCHEDULING_ENABLED` is on, because it states the configured intent, not
completed coverage. Narrowing or disabling the upcoming policy therefore shortens how far
forward visitors can browse months, from the next application start; check coverage before
claiming that a browsable month was actually acquired.

One dedicated scheduler thread serializes recurring work. If a long run delays the
other policy, its date window is evaluated at actual invocation. Missed occurrences
of a running policy and downtime are not reconstructed. #246 adds durable deferral of an
already-due window when UC-009 is occupied, with at most one slot per policy. Recorded
pending periodic work can drain after restart; historical work never starts at startup.
Terminal failures wait for the next cron, while an ownership-busy result keeps its slot;
see [historical coordination](#historical-synchronization).
All callers retain PostgreSQL active-provider ownership/fencing: contention with a
manual/repair run (or another instance) reports `SYNCHRONIZATION_ALREADY_RUNNING`
and does no provider work. Existing provider request paging, request timeouts and
bounded rate-aware retries remain unchanged;
total time/network/database work grows with Games in the configured window, while
memory stays bounded by page/aggregate size. Revisit cadence/windows if runs consume
an unacceptable part of their interval; historical maintenance remains in #246.

Coverage answers whether, and when, a release-date period was last scanned completely.
For example: has December 1997 ever been loaded? Read it with
`GET /actuator/cataloguecoverage?from=…&to=…`, which the
[backend README](../../backend/README.md#persistence-and-observability) documents.
[ADR-0017](../decisions/0017-discover-catalogue-members-automatically-from-igdb.md#coverage-and-freshness)
owns its semantics. A covered period with `failedGames` above zero was read completely, but
some Games kept their last valid state; look in that run's log for the reason. Investigate a
run that did not cover its window, such as `SYNCHRONIZATION_INTERRUPTED`, through the
retained run reports above. Judge a covered period's scan age against the decision at hand,
because no staleness threshold exists. An uncovered period is input for a deliberate
operator interval, never an automatic backfill.

Post-MVP (#247, implemented; awaiting owner review): the same evidence is readable without
SSH at `/administracion/catalogo`, reached from **Mi cuenta → Panel de administrador**
by a session signed in with the `catalogue-operator` role
([ADR-0028](../decisions/0028-authorize-catalogue-operators-with-a-bff-client-role.md)). It
shows the run in flight, flagged when it has stopped giving heartbeats, the last twenty
retained runs with their outcome code and counters, and coverage by decade, year and month
with each period's last complete scan. Its "recent" reading is a presentation policy of
seven days, not a threshold. The [private-dev runbook](../../deploy/private-dev/README.md#catalogue-operator-role)
owns how the role is configured, granted and revoked.

Post-MVP (#248, implemented; awaiting owner review): **Solicitar sincronización** on the
same page is the preferred ad-hoc path. The operator chooses an inclusive `from`–`to`
interval of at most 184 days between 1950 and 2099; the request answers once the run owns
the single synchronization, before any provider call, and the page then follows that run in
**Ejecución actual** and **Ejecuciones recientes**, reading the status every five seconds
until the run finishes. It is the ordinary UC-009 run: the same reconciliation, coverage,
progress logs and run report as every trigger, recorded under the `administration` trigger
meters. A run in flight from any trigger rejects the request with
`SYNCHRONIZATION_ALREADY_RUNNING` and starts nothing, so pause scheduling before planned
manual work; without provider credentials it is `SYNCHRONIZATION_DISABLED`. Nothing retries
automatically. The run executes on one background worker of the application instance that
accepted it: stopping or recreating the application interrupts it, and a run the stopping
application could not complete is recorded as abandoned by the next run start once its
heartbeat is older than `CATALOGUE_SYNC_ABANDON_RUN_AFTER`, as for the management command. Avoid recreating
the application while a requested run is in flight. Intervals longer than half a year belong
to several requests, one after another, until resumable backfill
([#246](https://github.com/rubhern/videogame-platform/issues/246)) exists.

Post-MVP (#246 spike; prepared, not yet proven on `vgpdev`): to measure one historical month
before that backfill is designed, the owner launches it with the
[historical synchronization pilot](../../deploy/private-dev/README.md#historical-synchronization-pilot).
It uses the management command below and leaves sanitized evidence; the
[research record](../research/historical-sync-pilot.md) owns how to read that evidence.

Post-MVP (#342, implemented; awaiting owner review): **Ejecución actual** can end the run in
flight, whatever started it: a scheduled, management or requested run.

- **Detener sincronización** is the normal action. After a confirmation it records a stop
  request on that run, by its identifier, and the panel reads *Detención solicitada* while
  the run continues. The worker stops at its next safe point, before its next provider page
  or Game. The provider call and Game write in progress finish first, bounded by the
  provider request timeout and retries and by the write transaction timeout. The run then
  appears in **Ejecuciones recientes** as *Detenida* (`SYNCHRONIZATION_STOPPED`, `partial` if
  it committed Games, otherwise `failed`). A run that reads its last page before a safe point
  keeps its ordinary result and notes that the stop came too late. Committed Games stay, no
  coverage is recorded for the interval, earlier coverage stays, nothing retries, and
  scheduling is unchanged. Request the interval again to cover it.
- **Abandonar ejecución** is the exceptional recovery for an orphaned run. It is offered only
  while the run is reported without recent heartbeats, older than
  `CATALOGUE_SYNC_ABANDON_RUN_AFTER`, typically after the application stopped mid-run.
  The server re-checks the heartbeat in the same statement that closes the run as
  *Abandonada por un operador* (`SYNCHRONIZATION_ABANDONED_BY_OPERATOR`) and frees ownership
  at once. A live worker's heartbeat wins: the answer is then `SYNCHRONIZATION_RUN_NOT_STALE`
  and the run keeps going, so stop it instead. A worker that resumes after an abandonment
  is refused at its next write, safe point or completion, records nothing more, and logs
  `SYNCHRONIZATION_OWNERSHIP_LOST`.

Neither action kills a process, interrupts a transaction or rolls back committed Games.
Before abandoning a run whose application is still up, check the application log for that
run's recent progress: a heartbeat older than the period means no page or Game finished in
that time, which a long provider outage with retries can also cause. A finished or replaced
run answers `SYNCHRONIZATION_RUN_FINISHED` or `SYNCHRONIZATION_RUN_NOT_FOUND` and changes
nothing. Without the product origin, the BFF or Keycloak, no break-glass equivalent
exists: an orphaned run is still taken over by the next run start once its heartbeat is
older than the period, the management command included.

The management command below stays the break-glass and diagnostic path: when the product
origin, the BFF session or Keycloak is unavailable, or when the blocking report is wanted
directly in the shell.

Runtime provider/trigger failure preserves last-valid Game state and stays outside
readiness and product reads. Inspect trigger outcome/duration and existing run/provider
counters via the [observability catalogue](observability.md); scheduled failure uses
`SYNCHRONIZATION_TRIGGER_FAILED`, while a returned report retains its existing stable
code. A terminal skipped trigger is visible in logs/meters even when no new durable run is
written; repeated ownership-busy admission is deferred rather than counted as a finished
scheduled trigger on every drain tick. The manual command remains available on private management for exceptional
recovery; pause scheduling before prolonged manual maintenance. The product API reaches
synchronization only through the operator's bounded request and the historical lifecycle,
which never hold a browser request open for provider work; no visitor request reaches it, and it is never a deployment prerequisite. Without
IGDB credentials UC-009 reports `SYNCHRONIZATION_DISABLED` and changes nothing.

Recurring catalogue synchronization has been exercised and accepted in private dev. 
Acceptance evidence is recorded in #155, not inferred from the
proven manual procedure below. After owner review and explicit deployment/host
authorization, enable scheduling in protected `runtime.env`, retaining secrets in
files; exercise both policies (temporarily shorten crons/windows if needed), observe
moving Madrid windows, successful/repeated idempotent results and a busy-run skip,
then verify readiness and release-exploration/search/game reads during a bounded provider
failure. Record evidence in #155 and restore the selected operational settings. To
recover, disable scheduling and recreate the same approved application digest; use
the exceptional manual command only as needed. Local/CI tests use fixtures/fakes and require no live credentials.

Local (proven): the `curl` commands in the backend README against `127.0.0.1:8081`,
with credentials only in the ignored `backend/.env`.

Private dev (proven on `vgpdev`; this is the source of the current `dev` catalogue):

1. Write the Twitch/IGDB client id and secret into the protected files
   `igdb-client-id` and `igdb-client-secret` under `PRIVATE_DEV_SECRETS_DIR`. Never
   place them in `runtime.env`, Compose metadata or a shell history.
2. Recreate the `application` container with the currently deployed digest. The
   entrypoint wrapper reads the two files once at start, so a running container keeps
   synchronization disabled until it is recreated.
3. Run the POST from inside the container, because the management port is
   container-internal and BusyBox `wget` is present for the health check:

   ```bash
   docker compose --env-file /etc/videogame-platform/dev/runtime.env \
     --file deploy/private-dev/compose.yaml --profile application \
     exec application wget -q -O - --header 'Content-Type: application/json' \
     --post-data '{"from":"<YYYY-MM-DD>","to":"<YYYY-MM-DD>"}' \
     http://127.0.0.1:8081/actuator/cataloguesync
   ```

   The call holds the session until the whole interval is reconciled; prefer a short
   interval per run. The same `exec` form with a plain `wget -q -O -` on the same URL
   reads the last run.
4. Verify the product afterwards through the private HTTPS origin (release exploration
   around today, search, one game page), as done for #45.
5. For exceptional manual-only operation, keep scheduling disabled. To disable all
   provider acquisition, empty both credential files and recreate the application.

Following a run: the POST blocks, so watch the application log from a second shell.
The same filter works for plain (local) and ECS JSON (private dev) lines:

```bash
docker compose --env-file /etc/videogame-platform/dev/runtime.env \
  --file deploy/private-dev/compose.yaml --profile application \
  logs --follow --since 5m application | grep 'Catalogue synchronization'
```

Locally, `docker compose logs --follow application | grep 'Catalogue synchronization'`
does the same. Expect `started`, then a `progress` line at least every minute, then one
`finished` line with the outcome, stable code, assessment and a `failures=stage/REASON=count`
tally.
`skipped` names `SYNCHRONIZATION_DISABLED` or `SYNCHRONIZATION_ALREADY_RUNNING`. A
`game failed` or `run failure` line names the stage and reason. A progress line with
rising counters means the run is advancing slowly. No progress line for more than about
two minutes and no `finished` line means one provider call or write is stuck beyond
its timeout and retries. The event model and levels are in
[observability](observability.md#application-logs). This procedure is verified by tests
and a local probe only; it is not yet proven on `vgpdev`. The
`catalogue.synchronization.*` meters remain the aggregate signal.

### When a synchronization needs an operator

Post-MVP (#246 spike follow-up; implemented, awaiting owner review). Every finished run keeps
its outcome, failed-Game count, failure tally and coverage. Its assessment,
[defined in observability](observability.md#correlation-logs-metrics-and-traces), adds only whether someone has to act:

| Assessment | What happened | Operator action |
|---|---|---|
| `clean` | Every Game succeeded | None |
| `data_quality` | The whole interval was read and covered; each failed Game failed with a known IGDB data inconsistency, such as a work the provider did not return or releases that repeat one another | None for the run. The Games keep their last valid state and are retried by the next run of that interval |
| `stopped` | An operator stopped it | None beyond the decision that caused it. The interval is not covered; request it again when wanted |
| `skipped` | Disabled, or another run owned the provider | None, unless credentials were expected |
| `attention` | Failed, interrupted before reading the whole interval, lost its ownership, or failed a Game for any other reason | Alerts. Read the tally and act on its reasons |

Intervene when the alert fires, and when the same interval fails the same Games with the
same data-quality reason on successive runs while its coverage stays old. Also intervene
when a data-quality reason appears that earlier runs never showed, or when a run's
data-quality failures are clearly out of line with comparable intervals: compare
`failed_games` with `data_quality_failed_games` on the synchronization dashboard and in the run
reports. These are review triggers, not thresholds: no evidence yet supports a percentage
that would mean a provider or importer problem. Review them when looking at the dashboard
after scheduled runs or a planned backfill, and open an issue for a recurring reason instead
of retrying blindly. Isolated data-quality failures in a covered interval need no action.

Featured releases (#151) rank each month by the IGDB Hypes signal that this same run
acquires with the existing Game request, together with first-release and distinct-product
evidence, for every Game it reconciles. Nothing
refreshes it between runs, so keep the current month's interval synchronized (for
example `from` the first and `to` the last day of the month) when TOPómetro should
reflect current attention. Signals older than `CATALOGUE_RELEASES_FRESHNESS_THRESHOLD`
make the selection stale: it stays served and says when attention was last observed.
The same run selects each Game's featured image from the artworks and screenshots of its
work request and its logo from one bounded logo lookup per page; nothing is fetched
between runs, and images are never downloaded. The finished line reports
`popularity[observed cleared unavailable]` and `media[images logos logos_unavailable]`;
a `page lookup unavailable` line names the provider failure of a logo lookup. Invalid
featured evidence keeps its last valid state; a valid missing/zero Hypes or missing first
release excludes a Game from ranking. The forward migration removes Visits data, so
re-synchronize the desired months after upgrading; it never converts Visits to Hypes.
`catalogue.featured.selection{freshness="stale"}` or `{status="popularity_unavailable"}`
on reads means the next run should cover that period, and a high `lead_image="fallback"`
share means its Games have no usable artwork or screenshot yet. Explicit
current-release repair refreshes featured evidence with each Game but does not look up logos.

The same Game request also acquires the summary, developer and publisher credits, genres and
game modes that the game page serves (#233). A valid answer replaces them, including clearing
what the provider no longer states; `details[unavailable]` on the finished line counts Games
that kept their last valid details because that metadata was invalid. Existing Games gain
these details only when a run or repair reconciles them again.

The same Game request also acquires the IGDB player score and its rating count (#255
phase 1 acquisition; phase 2 discovery presentation). The finished line's
`player_score[observed absent unavailable created updated removed unchanged]` group
reports what each run did:
- The first three count committed Games with a usable score, with a valid answer
  stating none or zero ratings, and that kept their last valid score because the
  answer was invalid or had no attribution page.
- The other four say what those writes did to stored usable scores.
- `player_score_invalid` mapping failures name the invalid answers.

There is no score backfill, expiry or vote threshold. A Game gains or refreshes its score
only when something reconciles it again:
- the scheduled near-term and upcoming windows refresh the Games those windows select;
- a manual interval refreshes the Games with a release date in it;
- the explicit current-release repair below can refetch every known Game under its
  existing approval rules.

Games outside those paths keep no score state, which Grafana reports as never checked
rather than as missing scores.

To check acquisition, open the synchronization dashboard's **IGDB player scores** row:
- After a run, its point in *Player-score results per run* should match the finished line.
- Normal re-runs are mostly *unchanged*. A burst of *removed* or *updated* scores, any
  *discarded* answers, or nonzero *Invalid score answers* deserves a look at the run's
  log and mapping failures.
- *Never checked* and the *30+ days* check-age band show how much of the catalogue the
  current windows leave untouched. That is expected for older Games and is never a
  reason to hide a score.

PostgreSQL allows one active run; an abandoned
worker is fenced after `CATALOGUE_SYNC_ABANDON_RUN_AFTER`, by the next run start or an
operator's abandonment, before a successor can write. Provider failure never deletes local Games, Releases or covers; the previous
valid publication keeps serving.


## Current provider-release repair

**Disposable-copy evidence (2026-10-02, #212):** the packaged candidate reconciled complete
IGDB aggregates for Young Suns, Ace Combat 8 and Rushcremental in a PostgreSQL 18 copy of
12,764 stored releases. Young Suns changed from eight to six current releases: absent
references `957758` and `957760` were removed, and public detail no longer exposed them.
Ace Combat 8 kept all six Full/Advance Access references; Rushcremental kept all six
Beta/Full references. Previews changed neither serving state nor validators. Repeating
each repair changed zero Games/releases and preserved the revision; eligibility stayed
unchanged in all three examples. The source catalogue was read only. The copy was restored
from its dump during the exercise. This proves bounded complete-Game repair, not a completed
full-catalogue or persistent private-dev cleanup.

The [repair tool](../../scripts/repair-release-stages.py) retains its existing filename and
private `releasestagerepair` command, but now uses complete Game reconciliation for both stage
and obsolete-reference repair. It also recomputes provider-only review under the
[domain rule](../architecture/domain/mvp-domain-model.md#boundaries-and-concepts), so known-stage
Games with stale review requirements are included. Use a fresh checkpoint after a policy
correction; a completed older checkpoint does not revisit Games. Validate preview, apply and
second-run idempotence on a disposable database before seeking approval for persistent apply.
Check that unambiguous review requirements clear while Unknown/conflicting evidence remains
protected. No Flyway data migration or direct SQL clearing is needed. Keep credentials in the
protected application environment,
never CLI arguments or reports. Use loopback management; forward private-dev management
locally when applicable. Persistent apply requires explicit owner approval after reviewing
the candidate, preview and recovery plan. The applied stage migration remains unchanged.

```bash
# Report only: date windows are planned; known Games are completely refetched and previewed.
python3 scripts/repair-release-stages.py \
  --management-url http://127.0.0.1:8081 --from 2024-01-01 --to 2026-12-31

# Apply only to the intended approved target; choose a protected checkpoint location.
python3 scripts/repair-release-stages.py \
  --management-url http://127.0.0.1:8081 --from 2024-01-01 --to 2026-12-31 \
  --apply --checkpoint /tmp/current-release-repair.json
```

Dates scope ordinary discovery replay; they do not restrict the subsequent known-Game
repair. Windows default to seven days (maximum 31), Game batches to ten (maximum 100).
The tool then keyset-pages every known typed IGDB Game, including Games with known stages,
undated releases, or no release remaining in a discovery window. Total work grows with all
known Games; normal success uses two provider requests per Game, with configured bounded
retries and release overflow checks. Provider calls happen outside write transactions.
The indexed cursor orders by internal Game ID; memory follows batch size plus the configured
per-Game release bound. No provider vocabulary, inferred stale tuple or direct SQL write
exists in the tool.

Each known Game delegates to ordinary synchronization's validation and atomic
create/update/delete transaction. Missing typed release references are removed only when
the complete aggregate is valid and the current evidence is owned exclusively by the IGDB
Release Date boundary. Local/curated, official, unreferenced and other-provider evidence is
preserved. Verified conflicting returned evidence retains its identity and value. Stage,
date/lifecycle, review and verification follow the normal reconciliation rules; stage has
no new eligibility policy. Content removal advances the existing revision and public
validators. Dry-run exercises these exact writes and constraints in a rolled-back
transaction, emits no listing event and changes neither catalogue, revision nor run history.
Its counters are predicted changes, not applied changes.

A failed/partial/skipped window or Game batch stops without advancing that checkpoint
cursor. Successful Games already committed remain valid; replay reconciles them idempotently
while the failing Game preserves its entire last valid state. Unavailable or invalid provider
evidence is not guessed or deleted. Fix/retry the failure rather than skipping its checkpoint.
The command is absent from the browser/product port and rejects cross-site writes.

Initial/final summaries count current Unknown releases, references eligible for inspection
(`repairableUnknown` is not a promise of supported stage evidence), evidence without supported
references, and known Games. Batch reports contain normal reconciliation counters, including
`deletedReleases`, and the outcome. Reports written before this counter existed read it as
zero; malformed existing fields remain errors. A complete checkpoint performs summaries only; use a fresh
checkpoint to revisit later provider changes. Checkpoints bind the target/options and algorithm
version; old stage-only checkpoints are rejected and require a fresh checkpoint. Files contain
no credentials and are written atomically with private permissions. Keep them outside `/tmp`
when restart durability matters.

The deterministic tool tests are `python3 scripts/test-repair-release-stages.py`; focused
backend tests cover completeness, ownership, same-reference identity, per-Game rollback,
idempotence, public evidence/eligibility and private management boundaries. The
[migration policy](database-migrations.md) owns deployment compatibility. Rolling back only
the application cannot restore deleted provider records; the existing
[backup and restore procedure](#backup-integrity-and-isolated-restore) owns recovery of data.

## Backup, integrity and isolated restore

Owner: [Backup, restore, rollback and host-loss recovery](../../deploy/private-dev/README.md#backup-restore-rollback-and-host-loss-recovery)
and `deploy/private-dev/bin/{backup,verify,restore}-private-dev`.

Proven end to end on 2026-09-19 (#44, PR #166):

1. `backup-private-dev` on `vgpdev` dumped `videogame_platform` and
   `videogame_keycloak`, encrypted both to the owner's public key (private key never on
   the host), wrote the manifest and `SHA256SUMS`, self-verified and applied retention.
2. `verify-private-dev-backup` passed on the host, and again after the backup was
   copied off-host. Copying it off the host is a manual owner step; it is what makes the
   backup "outside the host".
3. `restore-private-dev` rebuilt a clean volume in a distinct Compose project on an
   Ubuntu 24.04 WSL2 machine, bootstrapped roles from the protected files, restored
   both dumps and verified: Flyway schema version, presence of `catalogue.game` and
   `catalogue.game_release`, the `videogame-platform` realm and its account count.
4. Keycloak and the deployed application image started healthy against the restored
   state; readiness returned `UP`; `GET /api/v1/releases` served restored catalogue
   data; the realm exposed its OIDC discovery document.
5. The rehearsal project was destroyed with
   `docker compose ... --project-name <isolated-project> down --volumes`.

Verification scope, stated precisely:

- Identity mapping: proven through the restored Keycloak realm and accounts. Product
  `UserId` values are the `(issuer, subject)` pair ([ADR-0007](../decisions/0007-use-keycloak-as-the-initial-identity-provider.md)), so the Keycloak database is part of the
  irreplaceable state and is always dumped with the application database.
- Product-owned curation and application state: proven through the schema version and
  catalogue tables; the dump is a complete logical copy of `videogame_platform`.
- Ratings: included in the application dump because it is a complete logical copy of
  `videogame_platform`, but ratings-specific continuity after a restore has **not**
  been exercised and is not claimed as proven. #45 accepted the MVP without repeating
  the rehearsal after ratings existed.

Not backed up, by design: PostgreSQL roles and passwords, `runtime.env` and the
secret files. A restore recreates roles from the protected files, so those files are
themselves irreplaceable for a clean restore and must be preserved by the owner
outside the host. This is a documented limitation, not a proven procedure.

Destructive: `restore-private-dev --confirm-destroy-isolated-target` destroys the
named isolated project's volumes only; it refuses the live project name. On a fresh
host with no live stack, naming the live project as the isolated target is the
recovery path described below.

## Rollback versus forward fix

Owner: [Rollback versus forward fix](../../deploy/private-dev/README.md#rollback-versus-forward-fix)
and `deploy/private-dev/bin/assess-recovery-strategy`.

Decision rule: an applied migration is never reverted. Run the assessment with the
candidate image's packaged Flyway version and the live database:

- `ROLLBACK`: the candidate is schema-compatible. Redeploy its digest with the normal
  deployment command; the migration step finds nothing to apply.
- `FORWARD_FIX`: the database is ahead and the intervening migrations are not
  confirmed expand-only. Fix on `main`, publish, deploy the new digest.
- `REFUSE`: inputs are inconsistent; do nothing until they are explained.

Proven (#44): the assessment returned `ROLLBACK` for a previously successful image at
the same Flyway version and `FORWARD_FIX` when the schema was ahead. Every real
`vgpdev` recovery so far was a forward fix (#144–#149, #166, #170); an actual
redeployment of an older digest as a rollback has **not** been exercised.

## Host-loss recovery

Owner: [Host foundation](../../deploy/private-dev/README.md#host-foundation)
and [Host-loss recovery](../../deploy/private-dev/README.md#host-loss-recovery).

The flow composes owned steps: rebuild the host foundation, prepare the runtime and
protected files, restore the latest verified backup with `--host-loss-recovery` and a
current deletion-ledger copy, which replays every erasure before any deployment can
start, provision the smoke account, deploy the last-good digest, record the outcome.
The host-loss mode, its refusals and the deployment block are proven only offline
(`scripts/test-private-dev-backup-recovery.sh`, `scripts/test-private-dev-deployment.sh`);
a real host-loss restore with replay has **not** been exercised.

Accepted evidence boundary (owner decision on #44, 2026-09-19): the restore-and-run
part was proven on a compatible Ubuntu 24.04 environment. Physical-host provisioning,
boot-time systemd behaviour, OpenSSH/Tailscale setup, Tailscale Serve and the official
`deploy-private-dev` smoke were deliberately not re-run for recovery and remain
limitations, not claims. The original host preparation itself is proven once (#124,
#43).

## Secret rotation

**Not exercised.** No credential, client secret, Keycloak admin password, IGDB
credential or backup key has been rotated on `vgpdev` since creation. The
[platform design](../architecture/deployment/mvp-platform-and-delivery.md#private-dev-runtime-boundary)
states the constraint (replacing a file does not rotate an already-created PostgreSQL
role or imported Keycloak client; the owning service state and the file must change
as one reviewed operation), but there is no proven sequence. Do not improvise one
during an incident: open an issue, rehearse on the isolated restore project first,
and add the procedure here after it has run.

## Incident handling

Follow detect → contain → restore valid state → verify → record cause and follow-up.
The platform design's failure table defines the required behaviour; this section
records what has actually happened and what to collect.

Contain means stop making things worse: do not revert a migration, never run
`flyway clean` or a reset against `dev`, never delete and recreate a product user
(`sub` is identity), never publish a management, database or telemetry port, never
enable Tailscale Funnel or router forwarding, and never paste a secret into a log,
issue or chat.

| Symptom | Seen | Contain and restore | Evidence to collect |
|---|---|---|---|
| Deployment records `failure` at a smoke phase while the candidate answers | Yes: CSRF logout `403` behind Tailscale Serve (PR #170); OIDC profile and metric-order failures (#146–#149) | Leave the previous or candidate container as is; run the assessment; fix forward on `main` and redeploy. | Evidence JSON (phase, completed checks), smoke output with Problem Details, `docker compose logs application`, correlation id |
| Deployment stops at the migration phase | Not yet on `vgpdev` | Nothing was activated. Diagnose from the migration actor output; fix forward. Never edit an applied migration. | Migration actor output, current `flyway_schema_history` version from the assessment |
| Restore verifier fails on a real backup | Yes: verifier checked non-existent `public.*` tables (PR #166) | The live stack was untouched; fix the tool, rerun the isolated restore. | Restore output, backup id, manifest |
| Releases page shows `CATALOGUE_NOT_READY` | Expected after a clean restore/deploy without a sync | Not an incident; run a synchronization when catalogue data is wanted. | `GET /api/v1/releases` response |
| Synchronization reports failure or partial success | Not yet on `vgpdev` | Local reads keep serving the previous publication. Read the last run, fix credentials or wait out the provider, rerun the same interval (idempotent). | Last-run report, `catalogue.synchronization.*` meters, application log outcome code |
| Login loops locally after a fresh `.env` | Yes, locally | Documented in [local setup](local-setup.md#disposable-reset). | `local-dependencies.sh verify` output |
| Host unreachable or storage lost | Not yet | Host-loss recovery above. | Latest verified off-host backup id |

Record: for a deployment, the JSON evidence file; for a recovery or acceptance, an
issue comment in the form used on #44 and #45 (what was proven, the backup id,
digest, migration version and outcome, and the explicit evidence limitation). Open a
follow-up issue when a cause remains unresolved or corrective work is still needed.

## Password-recovery mail

Owner: [private-dev README](../../deploy/private-dev/README.md#password-recovery-mail-sink).
**Local and disposable-gate evidence, 2026-10-14 (#273):** Keycloak sends through
Mailpit; request, single-use link, successful reset and sign-in, reused, expired and
tampered links, unknown usernames and email addresses, and the existing-realm
configuration command are proven in the real identity gate. The workstation
`configure-mail`/`verify` path on an existing local realm
([local setup](local-setup.md#password-recovery-and-mailpit)), and the rollout, live
validation, end-to-end reset through the owner tunnel and reboot persistence on
`vgpdev` are **not exercised**; record their first run in #273.

## Personal-data requests and account revocation

**#274 locally prepared, pending owner review and CI:** the
[approved design and evidence boundary](data-protection.md#local-implementation-and-acceptance)
now have administrative implementations and draft product review pages. Existing
private-dev erasure/replay behavior remains available. Production cleanup execution
requires the [#275 deny/drain adapter](../../deploy/public-production/README.md#identity-maintenance),
its real-host drain acceptance (#276) and protected archive/proof/holds stores; no real
timer is installed. Real-VPS/cloud-only qualification remains #276 under the superseding
[storage decision](../architecture/deployment/mvp-platform-and-delivery.md#independent-secrets-data-and-recovery);
Lenovo is no longer mandatory. No host changes
or legal publication are authorized by local success.

Policy: the [data-protection procedure](data-protection.md#rights-request-procedure)
owns request verification, deadlines, recipients and legal limitations. The
[platform design](../architecture/deployment/mvp-platform-and-delivery.md#account-revocation-and-the-management-interface)
and [ADR-0027](../decisions/0027-revoke-and-end-bff-sessions-in-the-application.md)
own the revocation boundary. `deploy/private-dev/bin/personal-data` acts on one runtime; `personal-data-ledger`
runs on the owner's workstation and owns the ledger. The
[private-dev procedure](../../deploy/private-dev/README.md#personal-data-requests)
gives the exact host command. **Disposable-gate evidence only (#274):**
`scripts/validate-personal-data.sh` proves every path below with synthetic accounts;
it is **not exercised** on `vgpdev`.

Before acting, verify the requester with a fresh check of account control under the
[rights procedure](data-protection.md#rights-request-procedure); a matching sender
header alone is insufficient. The owner finds the Keycloak user ID in the Admin Console.
Never act on an identifier supplied by the requester, and give each request an opaque
reference (for example `REQ-2026-0001`), never an address or a name. Reply within one
month.

### The deletion ledger

An append-only file kept in two private copies (`0700` directory, `0600` file, both
refused otherwise), never on the host: the working copy on the owner's workstation
(`--ledger`) and one on storage independent of it (`--ledger-copy`), such as the owner's
drive that already holds the off-host backup copies and the backup key material. Each
line records an identifier, a step outcome or a time; no address, name, rating value or
reason.

`erase` and `retry` refuse to run without both copies, and every record, the request
first, is written and fsynced to both before the step it announces starts. So no
erasure can begin, and no step can run, unless its record would survive the loss of
either the workstation or the host. When an append was interrupted between the copies,
the next run completes the shorter one from the longer; copies that differ in any other
way stop every command until the owner compares them. A lost copy is rebuilt from the
other the same way. Never edit either by hand. Keep each entry at least 35 days after
completion, while any backup could restore the erased data; an incomplete entry is never
removed.

### Export (access and portability)

```bash
personal-data-ledger --ledger <ledger> --host-command "<host command>" \
  export --request <reference> --keycloak-user-id <id> --output <private-dir>/export-<reference>.json
```

The JSON holds the Keycloak profile (with any names stored before they stopped being
collected), the account's current Keycloak sessions with their start, last access and
IP address, every rating with its game, title, value and times, and the preferred
platforms (#295) with whether a choice was ever made. It is written once
as `0600` and never overwritten; nothing is stored on the host. Send it only to the
account's verified address, then delete the file. An export creates no ledger entry.

### Erasure

```bash
personal-data-ledger --ledger <ledger> --host-command "<host command>" \
  erase --request <reference> --keycloak-user-id <id>
```

The request is recorded before the first change, then each step in order, every one
recorded and safe to repeat: **revoke** (disable the Keycloak account, end its
Keycloak sessions, revoke its BFF sessions, repeat expecting zero), **delete-ratings**
(which also deletes the preferred platforms, #295, under its existing recorded name),
**delete-identity** and **verify** (no account, Keycloak session, rating, preferred
platform or BFF session left). Public aggregates read ratings at request time, so nothing is
recomputed. A step refuses to run out of order.

On any failure the entry stays incomplete and the account stays disabled once
revocation has run, so a half-erased account cannot act. Fix the cause and run
`personal-data-ledger … retry`, which repeats every step of every incomplete entry. A
failed BFF revocation (`HTTP 503`, no response) means the guarantee is missing: restart
the application, which discards every in-memory session, then retry. `status` lists
entries by reference and Keycloak user ID only.

### Restriction without erasure

To restrict or object (D10), disable the account in the Admin Console and sign it out
(**Sessions → Sign out**), then revoke its BFF sessions inside the application
container, because the management port is container-internal:

```bash
docker compose --env-file /etc/videogame-platform/dev/runtime.env \
  --file deploy/private-dev/compose.yaml --profile application \
  exec application wget -q -O - --header 'Content-Type: application/json' \
  --post-data '{"userId":"<UserId>"}' \
  http://127.0.0.1:8081/actuator/sessionrevocation
```

Take the `UserId` from `personal-data … identify --keycloak-user-id <id>`, which derives
it from the exact issuer the application runs with. `200 {"revokedSessions":N}` then a
repeat answering `0` completes it; `400 INVALID_USER_ID` means a wrong value; a `503` or
any doubt means restart the application. Re-enabling later needs nothing in the
application.

### Restore replay before reopening

A restored backup can predate erasures. The replay starts only the restored Keycloak,
refuses while the application runs, reapplies every entry, not only those after the
recovery point, verifies each one and records a receipt with the ledger's SHA-256. It
stops without changes if the target derives a different `UserId`, which means the
issuer changed. Two paths run it:

- `restore-private-dev --deletion-ledger <current copy>` replays as part of the restore,
  in a rehearsal or a host-loss recovery, and puts the receipt in the restore evidence.
  Host-loss recovery requires it, blocks every deployment from before the restore until
  the replay is verified, and offers `--resume-deletion-replay` when only the replay
  failed ([private-dev procedure](../../deploy/private-dev/README.md#host-loss-recovery)).
- From the workstation, against a restored project whose Keycloak already runs:
  `personal-data-ledger … --host-command "<restored-project host command>" replay
  --receipt <private-dir>/replay-<project>-<date>.json`.

Start the application only after a verified replay. An entry still incomplete in the
ledger is then finished with `retry` against the live runtime.

## Not covered

Real owner Telegram delivery remains unverified; use the [reviewed procedure](#telegram-delivery)
after explicit authorization. Independent host-loss notification is not implemented.

Public production, high availability, staging, automatic rollback,
OS and Docker update cadence, pinned-image refresh, disk pruning and hardware
monitoring cadence. The
[infrastructure review](../research/mvp-closeout-review/infrastructure-review.md)
lists these as proposals; none has an exercised procedure yet.

## Catalogue localization

**Local implementation evidence, 2026-10-04 (#235):** real IGDB sample inference with
both approved OPUS candidates, PostgreSQL source/derived persistence, idempotency,
claim fencing, last-valid preservation, bounded/restartable backfill, timeout/invalid
output and PostgreSQL-only Spanish HTTP responses. The owner reports that the model
selection already exists on `vgpdev` and that the helper was temporarily removed
during #160 preparation. The required base-runtime migration, promotion with unchanged
helper identity and reboot persistence in #257 are **not exercised** by this change.
Earlier issue closure is not evidence for this new lifecycle; shared-host resources
and translation quality have not been remeasured here.
[ADR-0022](../decisions/0022-localize-catalogue-content-during-acquisition.md) owns the
comparison evidence and resource extrapolation. [Backend README](../../backend/README.md#catalogue-translation-runtime-and-models)
owns model/runtime installation; [private-dev README](../../deploy/private-dev/README.md#catalogue-localization-helper)
owns the single standard-runtime migration and helper activation/update/rollback.

Normal synchronization commits provider source first, then attempts localization
through the narrow acquisition boundary. Known taxonomy references receive curated
Spanish labels without inference. Unknown taxonomy sources and English IGDB summaries
reuse an unchanged normalized fingerprint or request translation. Failed translation
never fails an otherwise valid Game. Source and provenance remain local; last-valid
Spanish content is retained and explicitly marked when its source changed. Editorial
and other-source summaries are outside enrichment ownership.

On a local backend, or an existing private management tunnel (never a product/public
route), start/restart a bounded backfill:

```bash
python3 scripts/localize-catalogue.py \
  --checkpoint .local-secrets/catalogue-localization-checkpoint.json \
  --batch-size 10 --max-batches 100
```

Use `--url` for the existing management forwarding procedure. Private dev keeps
Actuator container-internal; use the same management tunnel/container namespace as
current-release repair. A POST to `/actuator/cataloguelocalize` accepts `afterKind`,
`afterId` and `limit` (1–100); omitted cursor begins a fresh sweep. It returns the last
cursor, inspection/outcome counts and `completed`. The CLI saves acknowledged cursors
atomically, stops at its batch budget or first failed/busy batch and resumes with the
same checkpoint. Checkpoints bind the target URL and format; a different target
requires a fresh checkpoint. Interrupted requests replay safely. Failed batches retain their
original cursor, so successes are skipped on retry. A completed checkpoint stays
completed; start a fresh checkpoint for later source changes. No provider request,
scheduler, broker or complete-catalogue load occurs. A sweep's total database work
scales with visited targets; application memory stays proportional to the batch.

Inspect `catalogue.localization.*` and application/helper logs. A timeout is ambiguous:
the helper may still be computing, so its database reservation survives for up to five
minutes. Other failed/invalid work releases its reservation for retry; a crashed worker
is also recoverable after expiry. An expired worker cannot overwrite a successor.
The current source is rechecked under the publication/target locks before publication.

For source-preserving diagnosis, inspect a bounded set of pending rows rather than
printing the full catalogue or raw provider payloads:

```sql
SELECT g.game_id, t.claimed_until, t.translated_at
FROM catalogue.game_snapshot g
LEFT JOIN catalogue.game_summary_translation l ON l.game_id=g.game_id
LEFT JOIN catalogue.content_translation t
  ON t.fingerprint=catalogue.spanish_source_fingerprint(g.summary_text)
WHERE g.summary_kind='sourced' AND g.summary_language='en'
  AND g.summary_source_kind='external_provider' AND g.summary_source_name='IGDB'
  AND l.fingerprint IS DISTINCT FROM catalogue.spanish_source_fingerprint(g.summary_text)
ORDER BY g.game_id LIMIT 50;
```

A missing helper, bad model/tokenizer checksum, wrong non-root mount permissions,
invalid output or an exceeded source/output/token bound preserves the source and
last-valid content. Correct the configuration/model and replay; do not erase catalogue
state to clear a translation error. A source beyond the runtime's explicit bound is
preserved without truncation and needs operator investigation. Successful translations
persist across model/runtime restarts and upgrades.

Before owner acceptance of the new lifecycle on `vgpdev`:

1. Apply the [targeted one-time migration](../../deploy/private-dev/README.md#one-time-migration-to-the-standard-runtime),
   preserving the already installed model. Verify the manifest/licence and run a small
   representative backfill using actual local source. Review short/long translations,
   proper nouns and game terminology; accept or reject the quality limitation.
2. Measure inference and end-to-end batch throughput with the application, PostgreSQL,
   identity and telemetry running. Capture steady/peak RSS and CPU, verify limits and
   estimate historical backfill duration from that host's observed sample distribution.
   `tools/catalogue-localization/measure.py --model <dir> --samples <IGDB-json> --output <ignored-path>`
   repeats the bounded native model comparison; arrays are capped at 20 samples.
3. Interrupt/resume the same checkpoint and repeat a completed-content sweep; unchanged
   content must consume no new inference. Change a controlled source in a disposable
   copy, confirm refresh, and stop the helper to verify last-valid/new-source behavior.
4. With the helper stopped, read translated game details through the private browser
   HTTP boundary and verify source attribution, last-valid status and cache validators.
5. Restore the helper before promotion. Capture IDs/image/configuration for all
   persistent services, dispatch a normal application promotion and confirm only
   application changed. Verify the internal endpoint remains present, then confirm
   localizer recovery after an owner-approved host reboot. Missing/unhealthy/drifted
   helper refusal must precede migration/activation; it must not block existing reads.
6. Record the new lifecycle evidence on #257, linking earlier #235/#160 evidence
   where relevant. Full affected-area CI, owner diff review and private-host
   acceptance remain gates before closing this follow-up.

## Documentation portal

Owner: [Private-dev portal procedure](../../deploy/private-dev/README.md#documentation-portal).
The independent static portal is prepared for owner review. Local generation,
browser and container checks establish repository behaviour only; they do not prove
tailnet policy, HTTPS device access, restart persistence, host resource headroom or
non-tailnet IPv4/IPv6 denial. Those remain real-host acceptance under #38 after
explicit deployment approval. Application/Keycloak routes and the separate Grafana
ingress retain their own acceptance boundaries.

## Privacy phase-2 administration

These are local preparation commands, not authorization to use real accounts. Follow
the [approved policy and limits](data-protection.md#local-implementation-and-acceptance)
and the verified existing host-command procedure above. All paths below are
placeholders for private owner storage; do not put real source data in this repository,
CI, agent-accessible workspaces or private-dev mounts. Each directory must already
exist with owner-only access; archive roots and keys must be separate. The second
archive/ledger copy must have an independently qualified failure domain, which path
validation alone cannot establish.

Pass global arguments before the subcommand. For example, use the same reviewed
host command as the existing rights procedure, with its exact production HTTPS issuer
ending `/realms/gameometro` and the selected container names. Production mutation
requires a named application container even when stopped; a missing or uninspectable
container never proves session absence. The [candidate audit](../research/public-production-candidate-acceptance.md)
records the synthetic restore-time container and origin/issuer/database pairing;
#276 must qualify actual host permissions and identity before real use:

```bash
personal-data-ledger --ledger <owner-ledger> --ledger-copy <independent-ledger> \
  --host-command "<reviewed production host command>" \
  cleanup-unverified
```

This probes only, reports candidates and never enters maintenance. For any profiled
erasure/cleanup, add the following global options: `--archive-root <owner-archive>`,
`--archive-copy <independent-archive>`, `--proof-root <separate-proof-store>`,
`--holds <protected-holds-store>`, `--gnupg-home <off-VPS-owner-keyring>`,
`--recipient <full-encryption-key-fingerprint>` and `--signer <full-signing-key-fingerprint>`.
Use owner-unlocked GPG keys; pass no passphrase in arguments, environment or logs.
The existing `erase --request <opaque-reference> --keycloak-user-id <UUID>` selects
the ordinary profile automatically from the account’s explicit verification state in production. No individual ratings
are retained by that profile. A protected `<subject-UUID>.json` hold file selects a
particular-case profile; its closed source scope is `caseReference`, `reason`,
`gameIds` (at most 100 canonical UUIDs) and optional UTC `until` (at least initial
three-year expiry). No file is the normal case; malformed/missing stores refuse.

Only after #275/#276 drain acceptance may `cleanup-unverified --execute
--maintenance-command "<trusted adapter>"` be used or scheduled. The adapter accepts
`enter`, `check` or `exit`, each followed by `--run-id <UUID>`, and returns JSON
`{"issuer":"<exact HTTPS issuer>","runId":"<same UUID>","state":"drained"}`
for enter/check, or `state: "released"` for exit. It must deny all relevant native
identity mutation entry points and drain in-flight requests, serialize administrative
identity/restore/deployment operations, keep that fence until exit, and settle
management requests before release even after client timeout. A reload/sleep or
self-asserted JSON is insufficient. The production adapter is
[`identity-maintenance`](../../deploy/public-production/README.md#identity-maintenance) (#275); it is
prepared for local exercise through its linked production privacy transport; real-host
acceptance remains #276. The protected Admin credential must permit filtered
user reads, management-client discovery and effective client-role reads; inability
to establish role exclusion stops execution. Execute performs
key/storage preflight, locks the ledger, re-reads candidates, checks the fence and
uses the existing erasure steps; `exit` is attempted even if enter loses its response.
A failed exit is an operational failure requiring owner recovery, never proof that
normal identity service resumed. Inspect backlog/skips; resolve incomplete operations
with the same archive options and `retry --maintenance-command "<trusted adapter>"`
before another cleanup. Cleanup retry re-enters the fence and rechecks the original
recorded cutoff, verification, effective administrative roles and particular holds;
newly verified/protected accounts stop for private reconciliation. It reuses the first
capture, also when identity deletion already completed. Legacy private-dev `retry`
and restore `replay` keep their existing semantics. Missing-cutoff staged cleanup
entries require explicit reconciliation, not a reconstructed eligibility guess. Empty probes do not
retry committed incomplete operations; inspect `status` as part of owner monitoring.

Restore replay remains deletion-only for completed and legacy requests. If the ledger
contains an incomplete profiled operation, pass the same protected archive/key/proof/
holds options: replay must recover its first source without recapturing data or
resetting expiry. A missing first source, new ordinary-case hold or newly verified/
protected incomplete-cleanup account blocks replay and reopening. Reconcile privately
with the original runtime/source or a reviewed recovery decision; do not mark the
entry complete or discard its reference to bypass the refusal. Explicit cleanup
retry may finish original capture under the fence; replay never takes a new snapshot.

With the same protected archive/key options, `archive-verify --request <reference>`
checks both copies without outputting source fields. `archive-review --request
<reference>` records annual review; add `--hold-reason <opaque-case-reference>` and/or
`--extend-until <UTC-time>` for justified retention, or `--release-hold` after its
reason expires. Use an opaque case/ground reference in command arguments; keep
personal correspondence in protected storage. CLI output reports dates/hold state,
not private grounds/history. Review history is signed and bounded; staged `.pending`
files or conflicting signed copies stop verification and purge for owner reconciliation.
Never discard those files as routine cleanup or bypass a requested hold. `archive-purge --request <reference>` is dry-run; add `--execute`
only for the reviewed expired case. Repeat for explicitly selected cases; there is
no automatic whole-archive scan or purge schedule. Preserve destruction proofs when
restoring stores, then reapply purge to any restored matching ciphertext before
access. Signed partial destruction can resume; unsigned intent or inconsistent
review states require private reconciliation. Snapshot/media disposal and protected
hold/proof retention are separately verified operator responsibilities.

## Historical synchronization

Implemented under #246; owner review and deployed private-dev acceptance remain pending.
Use **Sincronización y cobertura → Sincronización histórica** with a catalogue-operator
session. Select explicit inclusive dates and whether completely covered months are omitted
or reprocessed, then review the confirmation. The initial intended historical interval is
recorded in the [pilot evidence](../research/historical-sync-pilot.md#completed-measurements-and-conclusion);
never run that full interval merely to accept the mechanism.

Inspect the requested interval, pending month, completed/omitted/remaining month counts,
progress, current available counters, processing totals, known data-quality failures,
start/activity times and stable last reason. Totals cover completed month attempts only;
Games can occur in more than one month and omitted months contribute no processing counts.
Unavailable in-flight counters are unknown, not zero. Progress is persisted on the backend;
closing the browser does not stop work. The ordinary **Cobertura** tab remains the canonical
view of scanned dates. Empty month checkpoints and known data-quality completion never
promise that every Game was acquired without errors.

**Detener histórico** requests its next safe stop and preserves committed Games and completed
months. It affects only the target's linked run, never another periodic/manual run. Wait for
**Detenida**. Resume deliberately repeats the pending partial month through ordinary
idempotent synchronization. **Deteniéndose** is acceptance of a stop request, not completion.
Scheduled updates get the next run slot after a month and history waits between chunks.
A due near-term or upcoming window stays pending, with at most one latest window per policy;
both take priority before history continues automatically. **Esperando otra sincronización**
with the scheduled-turn reason identifies this wait. You do not need to resume history after
a regular run; stopping history while it waits still stops only history.

Recorded periodic intent survives shutdown/restart and an enabled scheduler drains it without
requiring another due event. It can replay an ambiguous interrupted regular window through
UC-009 idempotence. This never starts a stopped/interrupted historical target. Disabling a
periodic policy cancels its pending slot; globally disabling scheduling cancels both at startup.
Terminal scheduled failures retain the existing next-cron retry policy, while shutdown
interruption preserves pending intent. No missed-downtime cron backlog is reconstructed.

After an operational failure, inspect the stable reason and existing acquisition/incident
procedures before **Reanudar histórico**. After restart, status reports interruption and no
historical work resumes automatically. If the linked ordinary run still appears active,
wait for it to finish or stop it through its named controls; abandon only a stale run whose
process is confirmed gone. Read status after an ambiguous command before repeating it.
An existing unfinished target cannot be replaced by a new date range.

For owner-authorized private-dev acceptance after review and promotion, choose a small
representative interval crossing a month boundary, record the target and run identifiers,
stop while the second month is active, restart the application only under the existing
maintenance procedure, and verify no provider work starts before explicit resume. Resume
and confirm the first completed month is retained and the second is replayed without new
duplicate identities/releases. Exercise a fully covered skip and an empty month against
controlled fixtures, review data-quality versus operational failure states, and verify a
scheduled update takes its turn. Record duration/resource evidence with the
[existing pilot procedure](../../deploy/private-dev/README.md#historical-synchronization-pilot).
The present change provides automated local evidence; it does not claim this deployed
acceptance or authorize deployment/full historical import.
