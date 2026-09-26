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
high availability, automatic recovery, alerting or a durable telemetry backend.

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
- Bounded telemetry receipt check with a disposable collector:
  `bash scripts/validate-private-dev-runtime.sh --telemetry-smoke`.
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

## Catalogue synchronization

Owner: [Backend README](../../backend/README.md) for the command semantics,
[ADR-0017](../decisions/0017-discover-catalogue-members-automatically-from-igdb.md)
for reconciliation, `backend/.env.example` for the bounds (`CATALOGUE_SYNC_*`).

Synchronization is one owner-triggered `POST /actuator/cataloguesync` with an
inclusive `from`/`to` date interval on the management port. It is never scheduled,
never reachable from the product API and never a deployment prerequisite. Without
IGDB credentials it reports `SYNCHRONIZATION_DISABLED` and changes nothing.

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
5. To keep synchronization disabled between runs, empty both files and recreate the
   application again.

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

PostgreSQL allows one active run; an abandoned
worker is fenced after `CATALOGUE_SYNC_ABANDON_RUN_AFTER` before a successor can
write. Provider failure never deletes local Games, Releases or covers; the previous
valid publication keeps serving.

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
