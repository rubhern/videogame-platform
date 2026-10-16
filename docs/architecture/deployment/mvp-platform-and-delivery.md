# Learning MVP platform and delivery design

- **Status:** Approved
- **Owner:** Ruben Hernandez
- **Scope:** Implemented private-dev platform and gated public-production design

This document owns environment purpose, deployment topology, artefact/configuration/
secret policy, migration/backup/recovery policy, and technical delivery. The
[delivery lifecycle](../../development/delivery-lifecycle.md) owns human workflow,
gates, review, acceptance, and release. The
[private-dev README](../../../deploy/private-dev/README.md) owns operator commands
and host procedures, and the [operations runbook](../../development/operations-runbook.md)
records which procedures have been proven on the real host and their evidence
boundary.

The accepted MVP established this platform baseline. The
[Product Brief](../../product/product-brief.md#public-release-and-sustainability)
owns post-MVP public-release direction and its pending decisions.

## Environments and topology

| Environment  | Purpose                                                                        | Data/access                                     |
|--------------|--------------------------------------------------------------------------------|-------------------------------------------------|
| `local`      | Development and focused proofs on supported WSL2                               | Disposable/seeded; loopback only                |
| `test`       | Per-job automated evidence                                                     | Generated fixtures; CI only                     |
| `dev`        | Persistent private integration, HTTPS, identity, delivery, telemetry, recovery | Non-sensitive learning data; owner tailnet only |
| `production` | Owner-approved release direction; technical proposal below, unbuilt and gated   | Independent durable data and public product access only |

`dev` is one owner-managed private Linux host (`vgpdev`: Ubuntu Server 24.04 LTS,
`x86_64`, 8 GB RAM, SSD, Docker Engine and Compose) under
[ADR-0019](../../decisions/0019-host-private-dev-on-an-owner-managed-linux-host.md).
Owner administration uses key-based OpenSSH over Tailscale; Tailscale Serve is the
only HTTPS edge and admits only the owner. Owner-approved application promotion
additionally permits an ephemeral tagged Actions identity to reach only the host's
OpenSSH port through the tailnet, with a forced promotion command; it receives no
product, identity or management access. This boundary is accepted under
[ADR-0023](../../decisions/0023-automate-owner-approved-private-dev-application-promotion.md);
host configuration and acceptance remain separate. Tailscale does not replace Keycloak or
product authorization. No public application, identity, database, telemetry or SSH
ingress is allowed on private-dev; router port forwarding, Tailscale Funnel and public DNS remain
prohibited. Machine addressing and tailnet identifiers stay outside the repository.

The superseded OCI path was removed; Git history and
[#42](https://github.com/rubhern/videogame-platform/issues/42) retain it. No paid or
trial-only substitute is authorized for private-dev. The owner's gated production
direction and separate purchase/exposure controls are described
[below](#public-production-target). HA, staging, Kubernetes,
distributed components, automatic broad sync and paid managed services remain deferred.

Local development can opt into the same metrics services and provisioning with
independent credentials and volumes. Only local development publishes loopback OTLP
for a host/IDE backend; private-dev Collector and Prometheus remain internal. The
[local setup guide](../../development/local-setup.md#local-metrics-and-logs)
owns local startup and reset procedures.

## Private dev runtime boundary

The default stack starts PostgreSQL, Keycloak, the Mailpit mail sink and one internal OpenTelemetry
Collector, Prometheus, Grafana, Alloy, single-instance Loki and catalogue-localizer.
The application is profile-gated and receives only runtime database
credentials; the deployment profile adds a one-shot migration actor and a browser
smoke runner. Repository configuration never selects or deploys an application digest
by itself.

The required catalogue-localizer is an acquisition helper on the
existing internal data network, with a read-only immutable model mount and explicit
CPU/RAM/process limits. The application always receives its internal endpoint.
It publishes no port and owns no durable state. Product readiness and PostgreSQL-only
reads remain independent of translation health, but application deployment requires
the expected acquisition runtime to be present and healthy before migration/activation.
[ADR-0024](../../decisions/0024-maintain-catalogue-localization-in-the-private-dev-runtime.md)
supersedes the initial private-dev optionality in ADR-0022; the
[private-dev README](../../../deploy/private-dev/README.md#catalogue-localization-helper)
owns migration, activation and rollback commands. Workstation activation stays opt-in.

Mailpit captures Keycloak's password-recovery email (#273) so local and private-dev
recovery is exercised without an external provider, credential or purchase. It
shares a dedicated network only with Keycloak; that network is non-internal solely so
its web UI can bind to host IPv4 loopback for the owner's SSH tunnel, without a
Tailscale Serve route. SMTP and POP3 publish nothing, nothing is relayed or forwarded,
and messages, which carry live reset links, stay in a temporary in-memory store
bounded by count and age and lost on restart. Mailpit is never an application
dependency. It is a non-production sink and the default transport of every
environment; local development and CI never use another.

Post-MVP (#274), private-dev may instead send its account mail through the approved
transactional provider's authenticated STARTTLS relay, selected explicitly on the
existing realm by an owner step and reverted the same way. Keycloak reaches the relay
over its existing outbound network; no service, port or route is added. Non-secret
relay inputs live in the protected runtime configuration and the SMTP credentials in
two optional protected secret files; Keycloak keeps the password in its database and
encrypted backups, as the production realm does. The relay cannot restrict
recipients, so the operating rule is that real mail goes only to owner-controlled
mailboxes. Production delivery evidence, sender reputation on the
public host and the production realm's own mail policy stay with the
[public release gates](../../development/delivery-lifecycle.md#public-production-gates).

PostgreSQL, Prometheus, Loki and the collector publish no host port.
Alloy accepts Docker syslog only on host IPv4 loopback UDP through a separate
ingress bridge, without joining the product edge network; its HTTP interface
is container-loopback only. Grafana binds only
to IPv4 loopback and is reached through the owner's SSH tunnel; it has no Tailscale
Serve route. The product and Keycloak HTTP
ports bind only to host IPv4 loopback, where Tailscale Serve terminates HTTPS on
separate tailnet-only ports. Keycloak management and application Actuator ports stay
container-internal. The application trusts forwarded scheme/port headers only from
the internal loopback peer (`SERVER_FORWARD_HEADERS_STRATEGY=NATIVE`, explained in
the Compose file) so the effective origin matches the external HTTPS origin that the
same-origin state-change check compares against; HSTS is enabled only for that
private HTTPS origin. The browser edge sends CSP with the approved IGDB cover CDN as
its sole external resource origin, framing and content-type sniffing disabled, and a
strict cross-origin referrer policy; CORS stays absent because the browser API is
same-origin. Executable and live validation treat IPv4 and IPv6 independently: host
acceptance requires separate non-tailnet evidence for the public IPv4 address and
every global IPv6 address.

The owner-approved documentation portal (#38) is an independent, optional static
service outside application promotion, migrations and readiness. Repository Markdown
remains authoritative; Astro Starlight, search indexes and diagram SVGs are derived
build output. A separate Compose project serves only that output, without product,
identity, data or telemetry network membership. Its upstream binds only to host IPv4
loopback; a dedicated owner-only Tailscale HTTPS endpoint preserves the existing
application/Keycloak endpoints and the separate Grafana ingress work (#296).
The [portal operator procedure](../../../deploy/private-dev/README.md#documentation-portal)
owns endpoint reservations, effective ACL/grants review, restart and isolated removal.
Configuration is prepared; real-host rollout and acceptance require explicit owner
approval. No public ingress, Funnel or recurring cost is authorized.

Real secrets are independent files below an owner-managed protected directory outside
Git. Compose grants each service only its required files; entrypoint wrappers read
them without putting values in Compose metadata or command arguments. Optional IGDB
files may remain empty to keep synchronization disabled. Replacing a file does not
rotate an already-created PostgreSQL role or imported Keycloak client: rotation must
update the owning service state and the file as one reviewed operation.

The database/role bootstrap and the parameterized Keycloak realm are shared
executable contracts with local development. Local Compose adds a synthetic-user
import that private dev does not mount; private dev instead provisions one marked,
non-personal deployment-smoke account through the private Admin API, never through
the shared realm import.

Post-MVP (#274), the persistent private-dev realm also applies the reviewed
[production identity policy](#production-realm-contract): email sign-in with an
email-only profile, the 18+ registration declaration, email verification, the
password, lockout, session and event policies. A reviewed Admin API step takes the
values from the production import, snapshots what it changes and can restore it; it
keeps the realm name, issuer, client and every account ID, so ratings stay attached.
Local development and CI keep the shared import's policy, and a freshly imported
private-dev realm needs the same step. The
[private-dev procedure](../../../deploy/private-dev/README.md#identity-policy-and-real-account-mail)
owns its account effects, rollout and rollback.

Private-dev metrics follow application OTLP → the existing internal Collector →
Prometheus → Grafana. The Collector exposes a Prometheus-format handoff only on the
internal telemetry network; the application stays independent of Prometheus. The
Collector still emits basic batch counts, and traces have no retained backend.
Prometheus retains local operational history with the approved initial seven-day /
512 MiB TSDB retention threshold. WAL, head chunks and compaction need additional
free disk: this threshold is not a filesystem quota. Metrics history is disposable,
not part of the irreplaceable PostgreSQL backup set.

Grafana provisions its dashboards and metrics/read-model datasources from Git,
with anonymous access and self-registration disabled. Its admin password comes from the protected
secret directory; the persistent Grafana database retains that credential, so file
replacement alone does not rotate it. Prometheus data and Grafana state survive
container recreation through separate named volumes. Grafana also joins the internal
data network for dedicated read-only PostgreSQL views; the separate reader has no
base-table/write/identity access. [Observability](../../development/observability.md#private-dev-dashboards)
owns read-model semantics and limitations. No additional service or database is added. Compose and the configuration under `deploy/private-dev` own pinned images, CPU/memory/PID/log bounds, scrape/query
limits and provisioning. The two added containers are an approved, reversible
private-dev cost (#158), not approval for distributed monitoring. Measure idle and
representative use on the host; limits alone do not demonstrate capacity.

Post-MVP #159 adds application console → Docker syslog → Alloy → Loki → existing
Grafana. [ADR-0021](../../decisions/0021-collect-private-dev-logs-with-alloy-and-loki.md)
records the proposed collection/security decision for owner review. The dedicated
internal logs network includes only Alloy, Loki and Grafana; the application has no
Loki connection. Only application stdout/stderr is aggregated. No daemon socket,
container discovery, identity/database logs or host journal is granted.

Loki retains short-lived disposable diagnostic history with filesystem TSDB and
compactor retention; its WAL/index/chunks and persistent compactor markers share a
separate named volume. Compose and [Loki configuration](../../../deploy/private-dev/loki/config.yaml)
own the exact resource, ingestion/query and retention bounds. The initial operator
budget is **4 GiB for Loki**, with **at least 2 GiB free host disk** before rollout
and during use. These are stop/review thresholds, not enforced filesystem quotas.
The configured rate permits roughly 0.85 GiB of uncompressed input per day; burst,
WAL/index, compaction and asynchronous deletion add overhead and retention lag.
Do not infer a hard disk maximum from the retention period. If measured usage crosses
either threshold, stop only logging ingestion/storage, preserve product/database
state and review volume growth before resuming. Host disk quota isolation remains a
revisit trigger if manual checks cannot protect the shared filesystem.

Alloy has no durable spool: bounded retries and memory intentionally trade delivery
for application independence. UDP/queue overflow/restarts and oversized records can
lose logs; Docker dual caching is also best effort. Prior Docker history is not
backfilled. Compose owns the memory ceilings, including the required localizer;
host/daemon, disk cache and deployment actors need additional headroom.
Review headroom under representative load rather than treating ceilings as
capacity evidence. Logs are excluded from the irreplaceable PostgreSQL backup set.

Telemetry, metrics/log storage and Grafana are never application startup/readiness
or request dependencies. Trace storage and remote export remain deferred. Private-dev
[Grafana-visible alerts](../../development/observability.md#private-dev-alerts) use
the existing stack. Native Telegram is prepared but disabled by default; its protected
activation and the separable optional Keycloak growth scrape are owned by the
[private-dev procedure](../../../deploy/private-dev/README.md#telegram-setup-and-delivery).
The single optimized private Keycloak image compiles native event-metrics support;
other consumers of the shared recipe retain its disabled build default.
Base configuration bounds collection to registration/verification and realm tags.
The growth overlay grants only the existing internal management scrape path. Base
operation keeps that endpoint isolated and retains the same identity policies.
No live delivery or public monitoring access is implied.
The [logs operator procedure](../../../deploy/private-dev/README.md#log-aggregation-and-exploration)
owns rollout, disk-pressure handling and recovery; the runbook distinguishes
repository configuration from host evidence.

## Artefact and delivery

The current supported runtime is `linux/amd64`. [ADR-0026](../../decisions/0026-defer-arm64-runtime-support.md)
defers ARM64 and owns the required restoration/verification gate before deploying
to an ARM64 host. Existing AMD64 deployment and rollback remain digest-based.

One immutable AMD64 OCI image contains the compiled frontend, BFF/API,
and modular monolith. It runs non-root, contains no environment configuration,
secrets, raw provider data, personal data, dev seed, or copied provider images, and
is identified by commit SHA and content digest rather than `latest`.

GitHub Actions validates pull requests without provider or deployment secrets and
never publishes or deploys from them. Trusted `main` builds and scans the same
image, produces SBOM/provenance evidence, and publishes to GHCR. Application delivery
remains **Continuous Delivery**: the owner dispatches the promotion workflow on main.
That dispatch is the sole human deployment approval and selects GitHub's exact dispatch
SHA. The workflow derives its unique trusted-main push build, current attempt and
immutable digest from retained publication evidence, refusing missing, stale or
ambiguous relationships without a latest-image or older-run fallback. It verifies
main ancestry and successful quality/security/publication evidence before the
credential-bearing job and rechecks the same tuple before connecting. The dev
environment retains its secrets and main-only deployment policy, with no reviewer,
wait timer or custom approval gate and no administrator bypass. Actor and triggering
actor must both be the owner. A merge never triggers deployment.
The original host promotion is proven; acceptance of the refined dispatch flow is
pending. The
[runbook](../../development/operations-runbook.md#deploying-an-immutable-digest)
owns its real-host evidence boundary. The
[delivery pipeline diagram](../diagrams/mermaid/delivery-pipeline.mmd) shows the flow.

Deployment runs under one non-blocking host lock: verify the supplied revision and
digest, run the selected image once as the migration actor, replace only the
application container with that digest, wait for candidate readiness, run the
deployment smoke against the candidate and the private HTTPS boundary, and record an
external JSON evidence file that holds no credentials or personal data.
Automation invokes this same command from a root-owned owner-installed checkout,
without uploading or executing candidate source on the host. Its restricted account
has no Docker group, interactive shell, forwarding or general sudo capability.
Host verification binds the candidate's Git deployment-contract fingerprint to the
clean installed checkout and a protected owner acknowledgement of the applied runtime.
The source/main CI metadata is rechecked through public GitHub read-only access;
the existing digest/tag/OCI checks bind the image to that successful source run.
Only a sanitized receipt crosses SSH; deployment logs stay protected on the host.
GitHub/API/network failures refuse promotion. Retained publication evidence may
expire; successful old runs without that evidence cannot be promoted through Actions.

Application promotion recreates **only the application** after the one-shot
migration. PostgreSQL, Keycloak, Mailpit, Grafana, Prometheus, Loki, Alloy, the Collector and
catalogue-localizer keep their explicit owner-reviewed rollout procedures.
The deployment preflight checks localizer health, the resolved Compose service hash
(including the protected immutable model directory) and local image identity without
recreating it. A helper outage blocks a new promotion while existing product reads
remain available.
Any change to deployment/runtime files or dependency bootstrap/configuration blocks
promotion until the owner has applied the appropriate separate rollout and installed
and acknowledged the matching checkout. Installing Git files alone never proves a
runtime upgrade. The retired localization-overlay application label is accepted for
transition to equivalent base settings; other active application overlays are refused.
Protected runtime environment/secret edits also retain their separate rollout and
rotation procedures. The normal manual deployment remains available for recovery.

Migration failure leaves the previous application running. A readiness or smoke
failure records a failed deployment even if another or older process still answers.
The mechanism implements no automatic rollback; backup, restore, rollback assessment
and host-loss recovery are separate owner-triggered controls. Deployment success is
not product acceptance or a named release.

## PostgreSQL, migrations, and recovery

One server hosts separate application and Keycloak databases and roles; business
modules retain logical table ownership. The selected application image runs once as
the dedicated Flyway actor with the migration role and exits before application
replacement; the running application keeps Flyway disabled and only the runtime role.
Destructive changes use expand/contract and explicit recovery. An applied migration
is never reverted: application rollback is allowed only while the older image is
schema-compatible, otherwise the recovery is a forward fix.

Irreplaceable state is the application and Keycloak databases (ratings, identity
mapping, product-owned curation). They are backed up together as per-database logical
dumps encrypted to the owner's public key, so the host holds no decryption material,
with a manifest, integrity sums and bounded retention; copying a backup off the host
is an owner step. Roles, passwords and secret files are not backed up: a restore
recreates roles from the protected files, which the owner must preserve separately.
Restore is rehearsed in an isolated Compose project, never against the live one.
Host-loss recovery composes the host foundation rebuild, restore and the normal
deployment. Catalogue provider data may be resynced, but personal, identity and
editorial state is never assumed disposable.

## Health, observability, privacy, and failure

Liveness reports process viability. Readiness proves local database and catalogue
store access only; IGDB, the cover CDN and telemetry outages never make the
application unready. Health never reveals topology or secrets. Telemetry uses bounded
labels, replaceable OpenTelemetry-compatible export, minimal retention, and no
personal data or credentials.

Catalogue synchronization has opt-in recurring inbound policies and an exceptional
internal management-port command, all calling `UC-009`; it is never a public request.
Scheduling and provider failures are excluded from readiness. Private-dev scheduling
is disabled by default until an owner-authorized exercise; executable Compose/runtime
configuration owns environment passthrough. The
[runbook](../../development/operations-runbook.md#catalogue-synchronization) owns
policy operation and the remaining evidence. PostgreSQL enforces one active run and
fences an abandoned worker before a successor can write; run history is retained in bounded quantity,
while durable interval coverage is kept apart from it and is never pruned with it.
[ADR-0017](../../decisions/0017-discover-catalogue-members-automatically-from-igdb.md)
owns the date interval, paging and partial-failure decisions.

| Failure                       | Required behaviour                                                                                           |
|-------------------------------|--------------------------------------------------------------------------------------------------------------|
| IGDB unavailable/rate-limited | Continue local reads; sync records failure; featured releases keep the last valid popularity signals and media |
| Image CDN unavailable         | Use product fallback (a featured card first tries the cover shown whole); keep game visible                  |
| No valid catalogue            | `CATALOGUE_NOT_READY`; no request-path provider call                                                         |
| Migration failure             | Do not activate new application                                                                              |
| Readiness/smoke failure       | Record deployment failure; recover by the assessed rollback or forward fix, never by reverting an applied migration |
| Backup failure                | Report recoverability failure; do not claim release success                                                  |
| Host loss                     | Rebuild the foundation, restore durable state from an encrypted backup, then redeploy and repeat the smoke   |

Standard OCI images, PostgreSQL logical backups, OpenTelemetry and private ingress
preserve portability. Hosting reconsideration triggers belong to ADR-0019.

## Public production target

**Owner-approved direction, unbuilt:** netcup VPS Lite 2, x86_64 / 8 GB RAM,
subject to availability, final price and qualification; open, non-commercial,
best-effort launch; EUR 20/month ceiling for all recurring costs. Purchase waits
for a production candidate. Local/CI development continues with temporary private-dev.
The [Product Brief](../../product/product-brief.md#public-release-and-sustainability)
owns that release mode, [ADR-0025](../../decisions/0025-host-public-production-on-a-deferred-single-vps.md)
proposes the durable topology, and the
[investigation](../../research/public-production-topology.md) records price/capacity evidence.

**Technical proposal awaiting review:** one Linux VPS, Docker Engine/Compose,
one application replica and one PostgreSQL server. No production Compose, proxy
configuration, host, DNS or workflow is created in #252. The following specifies
the minimal implementation target; it is not proof of a running system.

| Component / boundary | Target responsibility and exposure |
|---|---|
| HTTPS edge | Proposed Caddy with static routing, persisted ACME state and no Docker socket. Public TCP 80 only for redirect/ACME, TCP 443 for product/identity HTTPS. HTTP/3/UDP 443 is unnecessary initially. Default-deny unknown hosts and routes |
| Application | Existing immutable image serves React and same-origin BFF/API on one product origin; upstream port container-internal. No browser tokens, new CORS API or public provider proxy |
| Keycloak | Independent production realm/client/database. Dedicated HTTPS identity origin with canonical issuer; only necessary product-realm OIDC/login/resource routes public. Block admin console/API, master realm, health and metrics at the edge; admin hostname alone is not an access control |
| PostgreSQL | Same supported PG family; separate application/identity databases and roles; runtime role without DDL, one-shot migration actor. No host/public database port |
| Acquisition | Existing bounded sync and required CPU localizer with immutable read-only model. Internal network only, no request-serving role; schedule explicitly budgeted. Healthy expected helper required for promotion, following the current lifecycle pattern |
| Telemetry | Reuse internal Collector → Prometheus → Grafana patterns; Grafana through private administration/tunnel only. Initial production logs use bounded redacted Docker history; private-dev Loki/Alloy are not prerequisites for this target |
| Administration | Key-based owner SSH over existing eligible Tailscale; no public SSH listener. Database, Keycloak admin, Actuator/sync management, Docker and telemetry ports private. Protect provider/GitHub/tailnet administration with MFA |
| Recovery | Independent encrypted off-host copies of both databases, protected config/secrets reconstruction and immutable source/image receipts; no paid managed backup assumed |

Use separate edge, data/acquisition and telemetry networks with only required
membership; the proxy never joins the database network. Network names alone are
not ACLs: limit published ports and access from every attached peer. The identity
server remains reachable from the BFF at its canonical issuer without bypassing TLS
validation. Keycloak proxy configuration must overwrite forwarded host/scheme/client
headers, trust only the proxy and restrict backend access; see
[Keycloak guidance](https://www.keycloak.org/server/reverseproxy). Trust the known
production proxy peer/network rather than copying the private-dev loopback trust.
Test spoofed Host/forwarded values and exact redirect/logout origins. Preserve CSRF,
principal ownership, secure cookies and production-only HSTS/CSP/referrer controls.
Account revocation and its management interface follow
[below](#account-revocation-and-the-management-interface).

[Caddy automatic HTTPS](https://caddyserver.com/docs/automatic-https) requires
correct DNS, reachable validation ports and persistent writable certificate state.
Prepare static routes/local certificate tests before purchase; public issuance and
renewal need real DNS/network evidence. Publish AAAA only after independent IPv6
qualification. Docker NAT/published ports and host firewall must both be assessed
on IPv4/IPv6; do not claim protection from an SSH/HTTP-only firewall screenshot.
Private administration must work before opening the edge; provider console is a
protected break-glass path.

### Account revocation and the management interface

Disabling or deleting an account must stop its new operations immediately, including
through an already-open BFF session; a maximum session lifetime only bounds exposure
and is not revocation. Revocation must reach every store that can authenticate the
account and verify its outcome, failing closed.
[ADR-0027](../../decisions/0027-revoke-and-end-bff-sessions-in-the-application.md)
selects direct invalidation of BFF sessions. **Implemented in the application (#274),
not yet operated on a host:**

- Every OIDC sign-in registers its session in Spring Security's in-memory session
  registry. An authenticated session that is missing from it, or that is eight hours
  past the ID token's authentication time, ends on its next request; requests never
  extend that bound, and the 30-minute idle timeout is unchanged.
- The private `sessionrevocation` management operation expires every active session of
  one derived `UserId`, on every device, and answers only the count; a repeat answers
  zero. A revoked session reads as anonymous: personal APIs answer `401`, public reads
  continue, nothing is stored.
- Personal state changes run behind an in-process fence and recheck their session
  inside it, while revocation holds the fence exclusively. A successful answer
  therefore guarantees that no write by a revoked session is still running or can
  start, so deletion can follow at once instead of after a waiting period. Personal
  writes pause only while revocation waits, bounded by a 30-second settle limit; an
  unsettled answer or any failure requires an application restart, which discards
  every in-memory session. The [operations runbook](../../development/operations-runbook.md#account-disable-session-revocation-and-rating-deletion)
  owns the owner procedure.
- Logging out ends the application session and then, through standard OIDC
  RP-initiated logout with the session's ID token and the realm's exact post-logout
  URI, only that browser's Keycloak session. The token reaches the browser only in that
  redirect, never in an API response or JavaScript.
- These guarantees hold for one application replica with in-memory sessions; the ADR
  names what reopens the decision.

Management-interface requirements that #275 must preserve in the production runtime:

- Bind the management server to container loopback, the application default
  (`MANAGEMENT_SERVER_ADDRESS=127.0.0.1`), never to all interfaces as local Compose and
  private-dev do; publish no port and route nothing to it at the edge. The owner
  reaches it with `docker compose exec` over private administration, and smoke or
  health checks use the same in-container path instead of a peer network.
- Keep the management port separate from the product port. The revocation operation
  exists only on a separate management server, so merging the ports removes it rather
  than exposing it, and the procedure falls back to a restart.
- Keep the rejection of browser-initiated state-changing management requests; add no
  proxy, tunnel or credential-free route to the management server.
- Pin `OIDC_ISSUER_URI`, from which every `UserId` derives, and the post-logout URI
  (`APPLICATION_OIDC_POST_LOGOUT_REDIRECT_URI`) to the canonical public values, as the
  callback is.

Private-dev binds the management server to all container interfaces for its smoke
runner, so every peer on its edge and data networks, Keycloak, PostgreSQL, Grafana,
the localizer and the migration actor included, can invoke any management operation,
revocation as well as synchronization. Its owner-only tailnet and unpublished port
accept that for a private environment; changing it is a separate operational decision.

### Production realm contract

**Prepared, not deployed (#274 phase 2).** The production identity policy is the
[realm import](../../../deploy/public-production/keycloak/import/gameometro-realm.json)
in its own directory, independent of the shared local/private-dev realm; #274 owns its
content and [`validate-production-identity.sh`](../../../scripts/validate-production-identity.sh)
proves it with disposable Keycloak and Mailpit. The
[#274 research](../../research/public-release-rights-and-privacy.md#71-increment-1-production-realm-2026-10-15)
records the reviewed policy and its limits. #275 owns its runtime integration:

- Mount only `deploy/public-production/keycloak/import/`, read-only, as Keycloak's
  import directory; never add the shared realm or the local user import to it.
- Supply every `${…}` input the import names, plus the theme's `APPLICATION_PUBLIC_ORIGIN`
  (`https://` and the same public host), from protected production files, and refuse to
  start when one is empty: startup import keeps an unresolved placeholder literally.
- Use the themed image built from the shared Dockerfile at a pinned identity; set the
  canonical HTTPS hostname, proxy trust and TLS; keep `info` logging, the HTTP access
  log off and no event listener added outside the realm.
- Startup import applies only when the realm is absent. A later policy change needs a
  reviewed apply step against the live realm, never a re-import over accounts.

The approved realm name fixes the issuer path `/realms/gameometro`. The full issuer,
the identity HTTPS origin plus that path, must be fixed before the first real account
is created and must then stay stable: every rating is keyed by a `UserId` derived from
it, so a changed issuer detaches all ratings from their accounts.

### Resource and work envelope (proposal, not capacity evidence)

Begin qualification with explicit memory/CPU/PID/connection/request/log/storage
bounds. This initial envelope is a local/CI hypothesis, not a claim that these
values work on netcup; executable Compose will own the final limits.

| Memory allocation for qualification | GiB |
|---|---:|
| Application, including JVM native memory | 1.50 |
| PostgreSQL including charged page cache | 0.75 |
| Keycloak including JVM native memory | 0.75 |
| Catalogue localizer | 0.75 |
| Collector / Prometheus / Grafana total | 0.625 |
| HTTPS edge | 0.125 |
| Steady service ceilings total | **4.50** |
| One deployment/backup actor, serialized | 0.50 |
| Host kernel/Docker/OS allowance | 1.00 |
| Unassigned nominal remainder at 8 decimal GB (~7.45 GiB) | **~1.45** |

The provider's actual reported RAM decides usable headroom. Require at least
20% available host memory under the qualification workload, no OOM/sustained swap
thrashing, and room for migration/restart. If the nominal envelope misses that
target or a service limit is insufficient, revise allocations/load within the
selected plan and repeat the focused proof before approval; never call container
ceilings measured resident usage. Shared vCores are not reserved physical cores.
Serialize deployment/backup/heavy sync, bound translation concurrency and keep
product/database CPU headroom; do not sum CPU quotas as a throughput guarantee.
No pool increase or search-index change is assumed by this topology.

Storage budget covers PG data/indexes/WAL, identity, model, telemetry, rotated
logs, two retained image generations and dump/restore scratch. Require >=25%
free filesystem space under the representative dataset plus job/image overlap.
Retention is not a disk quota. Stop sync/log ingestion before exhausting storage,
preserve identity/ratings, and stop new deployments when recovery headroom is lost.
Record real disk growth/I/O, shared-CPU steal and p95/pool/resource observations;
the [qualification plan](../../research/public-production-topology.md#capacity-evidence-assumptions-and-stop-conditions)
defines the bounded experiment. No Lenovo result substitutes for VPS measurement.

### Independent secrets, data and recovery

Production secrets are separate from local/private-dev and CI, stored in protected
host files outside Git, mounted only into their consumers. Compose secrets on one
host are file delivery, not an encrypted vault. Keep restricted owner permissions,
no secret values in container metadata/arguments/logs, and coordinated role/client/file
rotation. Securely retain reconstruction material and the backup decryption key
outside the VPS/account. Application sessions, real accounts and ratings never come
from private-dev fixtures or database cloning.

Start from forward migrations, an independently configured realm and fresh local
catalogue synchronization after provider acceptance. Production registration/email
policy must satisfy [release readiness](../../research/public-production-topology.md#identity-and-abuse-findings-from-the-repository)
and the [owner-approved account baseline](../../product/product-brief.md#public-release-and-sustainability).
Seed no local users; an isolated, non-personal deployment-smoke account requires
separate credentials/lifecycle and does not prove real email delivery.

Reuse logical dumps encrypted to the owner's public key, checksummed manifests,
bounded retention and isolated restore. Proposed initial recovery objectives for
owner acceptance are **RPO <=24 hours for the last verified off-host pair** and
**RTO <=48 hours after incident detection**; neither is measured or a commercial SLA.
Backup history, off-host copies included, is owner-limited to **35 days** (#274);
propose seven daily copies plus one weekly copy for four weeks. Size the independent
destination and prove the scheduled copy/check cadence; a dump left on the VPS is
not a backup against host/account loss. Failure/age must reach the owner through a
tested private channel. If this cannot operate within the budget, do not open accounts.

Two per-database dumps do not create one cross-database transaction. Quiesce rating
writes and registration/identity mutation while capturing the pair, verify the
application-principal/Keycloak relationship on isolated restore, and record the
common recovery point. Restoration must reapply post-backup deletion requests before
reopening and invalidate old sessions as appropriate. Keep a minimal protected deletion
ledger outside both databases and the VPS while any backup able to restore the deleted
data exists; replay it idempotently and verify the result before reopening. The
[#274 research](../../research/public-release-rights-and-privacy.md#4-rights-handling-revocation-and-deletion)
holds the proposed procedure. Roles/secrets are
reconstructed separately. Provider snapshots can aid rollback but cannot replace
independent, encrypted, tested recovery. Rehearse host-loss reconstruction privately
before public acceptance; measure achieved RPO/RTO and record limitations.

### Immutable deployment and failure

CI builds/scans the existing frontend/BFF/monolith image; select a trusted-main
source SHA and immutable `image@sha256:…` with retained checks/SBOM/provenance.
Production dependencies (including the themed Keycloak image and localizer/model)
also need reproducible pinned identities; never build on the VPS, deploy `latest`,
or infer a digest from a movable tag. Record configuration fingerprint, source,
image/model identities, migration version and sanitized deployment/acceptance receipt.

Initially use owner-triggered manual promotion over private administration, reusing
the current preflight/lock/migration/readiness/smoke pattern in a production-specific
contract. Private-dev Actions credentials/forced commands authorize only private-dev;
do not reuse them for production or add unattended deployment. A production Actions
promotion path is optional later, not a launch prerequisite. Migration is a one-shot
actor before application replacement; dependencies/config/secrets have separate
reviewed rollouts. No automatic merge-to-production or extra staging environment.

Retain the previous digest and recovery point. Failed migration prevents activation;
failed readiness/smoke is recorded as failure. An application rollback requires
schema compatibility; otherwise use a forward fix or assessed restore. Never revert
an applied migration. One replica means deployment interruption and no HA promise.
IGDB/CDN/helper/telemetry failures preserve database-only reads; overload returns
controlled bounded failure and admission restrictions instead of unbounded queues.
Close public ingress on an unresolved security/data-integrity failure.

The [public gates](../../development/delivery-lifecycle.md#public-production-gates)
separate preparation, purchase and exposure. The operations runbook will describe
production procedures as proven only after real-VPS execution. Private-dev remains
private integration until the owner separately approves retirement after launch.
