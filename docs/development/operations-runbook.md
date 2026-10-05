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

The MVP is private, single-host and zero-cost. Nothing here claims public production,
high availability, automatic recovery or alerting. Retained private-dev metrics and aggregated logs are
disposable operational history.

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
| Trigger a local catalogue synchronization | [Backend README](../../backend/README.md#persistence-and-observability) (`POST /actuator/cataloguesync` on `127.0.0.1:8081`) |

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
- Readiness covers only PostgreSQL and the catalogue store. IGDB, the cover CDN and
  the collector being down never make the application unready.
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
explained in [observability](observability.md#synchronization-panel-queries-and-sparse-events).
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
2. Confirm the dependency stack is healthy (`ps` above) and that nobody else is
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
[#160](https://github.com/rubhern/videogame-platform/issues/160); keep it open while
this acceptance remains pending. A failed or interrupted workflow requires inspecting
protected host logs/evidence before another owner action, even if the application answers.

## Catalogue synchronization

Owner: [Backend README](../../backend/README.md) for the command semantics,
[ADR-0017](../decisions/0017-discover-catalogue-members-automatically-from-igdb.md)
for reconciliation,
[`application.yaml`](../../backend/src/main/resources/application.yaml) for executable
policy defaults and bounds, `backend/.env.example` for local configuration and
[`runtime.env.example`](../../deploy/private-dev/runtime.env.example) for private-dev
opt-in/overrides. These are acquisition policies, not product/business invariants.

Post-MVP (#155, implemented; private-dev exercise pending): opt-in recurring near-term
and upcoming policies invoke the same UC-009 application path as the exceptional
`POST /actuator/cataloguesync`. `CATALOGUE_SYNC_SCHEDULING_ENABLED` defaults off,
independently of credentials. Each policy accepts a six-field Spring cron; `-`
disables only that policy. Non-negative past/future day offsets derive an inclusive
window from one evaluation of the application clock in `Europe/Madrid`; neither the
host timezone nor the UTC provider date-query boundary changes this evaluation.
Configuration changes take effect on application recreation. Invalid cron/windows
are configuration errors detected at startup.

Initial policy rationale: daily off-peak near-term refresh covers the supported
four-week recent and upcoming discovery horizons. Weekly upcoming refresh covers
roughly six months ahead, keeping wider acquisition less frequent. The default start
times are staggered to reduce contention; the precise crons/offsets stay in
`application.yaml` and can be tuned after observing run duration and provider volume.
Their overlap is deliberate and uses stable-reference idempotence, with no scheduling
de-duplication or coverage claim.

One dedicated scheduler thread serializes recurring work. If a long run delays the
other policy, its date window is evaluated at actual invocation. Missed occurrences
of a running policy and downtime are not replayed; no acquisition runs on startup,
no persistent checkpoint is added, and failed/skipped runs wait for the next cron.
All callers retain PostgreSQL active-provider ownership/fencing: contention with a
manual/repair run (or another instance) reports `SYNCHRONIZATION_ALREADY_RUNNING`
and does no provider work. Existing provider request paging, request timeouts and
bounded rate-aware retries remain unchanged;
total time/network/database work grows with Games in the configured window, while
memory stays bounded by page/aggregate size. Revisit cadence/windows if runs consume
an unacceptable part of their interval; historical maintenance/coverage remains in
#246/#245.

Runtime provider/trigger failure preserves last-valid Game state and stays outside
readiness and product reads. Inspect trigger outcome/duration and existing run/provider
counters via the [observability catalogue](observability.md); scheduled failure uses
`SYNCHRONIZATION_TRIGGER_FAILED`, while a returned report retains its existing stable
code. A skipped trigger is visible in logs/meters even when no new durable run is
written. The manual command remains available on private management for exceptional
recovery; pause scheduling before prolonged manual maintenance. Synchronization is
never reachable from the product API and never a deployment prerequisite. Without
IGDB credentials UC-009 reports `SYNCHRONIZATION_DISABLED` and changes nothing.

Private-dev acceptance for recurring behavior is **pending**, not inferred from the
proven manual procedure below. After owner review and explicit deployment/host
authorization, enable scheduling in protected `runtime.env`, retaining secrets in
files; exercise both policies (temporarily shorten crons/windows if needed), observe
moving Madrid windows, successful/repeated idempotent results and a busy-run skip,
then verify readiness and recent/upcoming/search/game reads during a bounded provider
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
4. Verify the product afterwards through the private HTTPS origin (recent, upcoming,
   search, one game page), as done for #45.
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
`finished` line with the outcome, stable code and a `failures=stage/REASON=count` tally.
`skipped` names `SYNCHRONIZATION_DISABLED` or `SYNCHRONIZATION_ALREADY_RUNNING`. A
`game failed` or `run failure` line names the stage and reason. A progress line with
rising counters means the run is advancing slowly. No progress line for more than about
two minutes and no `finished` line means one provider call or write is stuck beyond
its timeout and retries. The event model and levels are in
[observability](observability.md#application-logs). This procedure is verified by tests
and a local probe only; it is not yet proven on `vgpdev`. The
`catalogue.synchronization.*` meters remain the aggregate signal.

Featured releases (#151) rank each month by the IGDB Hypes signal that this same run
acquires with the existing Game request, together with first-release and distinct-product
evidence, for every Game it reconciles. Nothing
refreshes it between runs, so keep the current month's interval synchronized (for
example `from` the first and `to` the last day of the month) when Destacados should
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
on reads means the next run should cover that month, and a high `lead_image="fallback"`
share means its Games have no usable artwork or screenshot yet. Explicit
current-release repair refreshes featured evidence with each Game but does not look up logos.

The same Game request also acquires the summary, developer and publisher credits, genres and
game modes that the game page serves (#233). A valid answer replaces them, including clearing
what the provider no longer states; `details[unavailable]` on the finished line counts Games
that kept their last valid details because that metadata was invalid. Existing Games gain
these details only when a run or repair reconciles them again.

PostgreSQL allows one active run; an abandoned
worker is fenced after `CATALOGUE_SYNC_ABANDON_RUN_AFTER` before a successor can
write. Provider failure never deletes local Games, Releases or covers; the previous
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
protected files, restore the latest verified backup, provision the smoke account,
deploy the last-good digest, record the outcome.

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

## Not covered

Public production, high availability, staging, automatic rollback, scheduled
synchronization, alerting, OS and Docker update cadence, pinned-image refresh, disk
pruning and hardware monitoring cadence. The
[infrastructure review](../research/mvp-closeout-review/infrastructure-review.md)
lists these as proposals; none has an exercised procedure yet.

## Catalogue localization

**Local implementation evidence, 2026-10-04 (#235):** real IGDB sample inference with
both approved OPUS candidates, PostgreSQL source/derived persistence, idempotency,
claim fencing, last-valid preservation, bounded/restartable backfill, timeout/invalid
output and PostgreSQL-only Spanish HTTP responses. Private `vgpdev` installation,
shared-host resources and owner translation-quality acceptance are **not exercised**.
[ADR-0022](../decisions/0022-localize-catalogue-content-during-acquisition.md) owns the
comparison evidence and resource extrapolation. [Backend README](../../backend/README.md#catalogue-translation-runtime-and-models)
owns model/runtime installation; [private-dev README](../../deploy/private-dev/README.md#catalogue-localization-helper)
owns helper activation/update/rollback.

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

Before owner acceptance on `vgpdev`:

1. Install the pinned model/runtime, verify the manifest/licence and run a small
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
5. Record host evidence on #235. Full affected-area CI, owner diff review and this
   private-host acceptance remain gates before closing the issue.
