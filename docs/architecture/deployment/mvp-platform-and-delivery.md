# Learning MVP platform and delivery design

- **Status:** Approved
- **Owner:** Ruben Hernandez
- **Scope:** Current private, zero-recurring-cost platform and delivery

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
| `production` | Intended future environment; gated, not approved or available                  | Undefined                                       |

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
ingress is allowed; router port forwarding, Tailscale Funnel and public DNS remain
prohibited. Machine addressing and tailnet identifiers stay outside the repository.

The superseded OCI path was removed; Git history and
[#42](https://github.com/rubhern/videogame-platform/issues/42) retain it. No paid or
trial-only substitute is authorized. Public production requires the linked
public-release decisions before provisioning or exposure. HA, staging, Kubernetes,
distributed components, automatic broad sync and paid managed services remain deferred.

Local development can opt into the same metrics services and provisioning with
independent credentials and volumes. Only local development publishes loopback OTLP
for a host/IDE backend; private-dev Collector and Prometheus remain internal. The
[local setup guide](../../development/local-setup.md#local-metrics-and-logs)
owns local startup and reset procedures.

## Private dev runtime boundary

The default stack starts PostgreSQL, Keycloak and one internal OpenTelemetry
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

Private-dev metrics follow application OTLP → the existing internal Collector →
Prometheus → Grafana. The Collector exposes a Prometheus-format handoff only on the
internal telemetry network; the application stays independent of Prometheus. The
Collector still emits basic batch counts, and traces have no retained backend.
Prometheus retains local operational history with the approved initial seven-day /
512 MiB TSDB retention threshold. WAL, head chunks and compaction need additional
free disk: this threshold is not a filesystem quota. Metrics history is disposable,
not part of the irreplaceable PostgreSQL backup set.

Grafana provisions four dashboards and its metrics/read-model datasources from Git,
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
or request dependencies. Trace storage, alerting and remote export remain deferred.
The [logs operator procedure](../../../deploy/private-dev/README.md#log-aggregation-and-exploration)
owns rollout, disk-pressure handling and recovery; the runbook distinguishes
repository configuration from host evidence.

## Artefact and delivery

One immutable multi-architecture OCI image contains the compiled frontend, BFF/API,
and modular monolith. It runs non-root, contains no environment configuration,
secrets, raw provider data, personal data, dev seed, or copied provider images, and
is identified by commit SHA and content digest rather than `latest`.

GitHub Actions validates pull requests without provider or deployment secrets and
never publishes or deploys from them. Trusted `main` builds and scans the same
index, produces SBOM/provenance evidence, and publishes to GHCR. Application delivery
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
migration. PostgreSQL, Keycloak, Grafana, Prometheus, Loki, Alloy, the Collector and
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
fences an abandoned worker before a successor can write; run history is retained in bounded quantity.
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
