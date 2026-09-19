# Learning MVP platform and delivery design

- **Status:** Approved
- **Owner:** Ruben Hernandez
- **Scope:** Private, zero-recurring-cost learning platform

This document owns environment purpose, deployment topology, artefact/configuration/
secret policy, migration/backup/recovery policy, and technical delivery. The
[delivery lifecycle](../../development/delivery-lifecycle.md) owns human workflow,
gates, review, acceptance, and release. The
[private-dev README](../../../deploy/private-dev/README.md) owns operator commands
and host procedures, and the [operations runbook](../../development/operations-runbook.md)
records which procedures have been proven on the real host and their evidence
boundary.

## Environments and topology

| Environment  | Purpose                                                                        | Data/access                                     |
|--------------|--------------------------------------------------------------------------------|-------------------------------------------------|
| `local`      | Development and focused proofs on supported WSL2                               | Disposable/seeded; loopback only                |
| `test`       | Per-job automated evidence                                                     | Generated fixtures; CI only                     |
| `dev`        | Persistent private integration, HTTPS, identity, delivery, telemetry, recovery | Non-sensitive learning data; owner tailnet only |
| `production` | Deferred/prohibited                                                            | Undefined                                       |

`dev` is one owner-managed private Linux host (`vgpdev`: Ubuntu Server 24.04 LTS,
`x86_64`, 8 GB RAM, SSD, Docker Engine and Compose) under
[ADR-0019](../../decisions/0019-host-private-dev-on-an-owner-managed-linux-host.md).
Owner administration uses key-based OpenSSH over Tailscale; Tailscale Serve is the
only HTTPS edge and admits only the owner. Tailscale does not replace Keycloak or
product authorization. No public application, identity, database, telemetry or SSH
ingress is allowed; router port forwarding, Tailscale Funnel and public DNS remain
prohibited. Machine addressing and tailnet identifiers stay outside the repository.

The superseded OCI path was removed; Git history and
[#42](https://github.com/rubhern/videogame-platform/issues/42) retain it. No paid or
trial-only substitute is authorized. Public production, HA, staging, Kubernetes,
distributed components, automatic broad sync and paid managed services remain
deferred.

## Private dev runtime boundary

The default stack starts PostgreSQL, Keycloak and one internal OpenTelemetry
Collector. The application is profile-gated and receives only runtime database
credentials; the deployment profile adds a one-shot migration actor and a browser
smoke runner. Repository configuration never selects or deploys an application digest
by itself.

PostgreSQL and the collector publish no host port. The product and Keycloak HTTP
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

Telemetry is one replaceable, internal-only collector, not a dashboard/storage stack.
It accepts application OTLP metrics and traces, bounds memory and batch size, and
emits only basic count diagnostics into size-limited container logs. It holds no
secret and is not a readiness dependency. A durable telemetry backend, dashboards,
alerting and remote export remain deferred until measured value justifies their host
cost.

## Artefact and delivery

One immutable multi-architecture OCI image contains the compiled frontend, BFF/API,
and modular monolith. It runs non-root, contains no environment configuration,
secrets, raw provider data, personal data, dev seed, or copied provider images, and
is identified by commit SHA and content digest rather than `latest`.

GitHub Actions validates pull requests without provider or deployment secrets and
never publishes or deploys from them. Trusted `main` builds and scans the same
index, produces SBOM/provenance evidence, and publishes to GHCR. Deployment promotes
an already validated digest only when the owner invokes the deployment command; it is
never automatic. The
[delivery pipeline diagram](../diagrams/mermaid/delivery-pipeline.mmd) shows the flow.

Deployment runs under one non-blocking host lock: verify the supplied revision and
digest, run the selected image once as the migration actor, replace only the
application container with that digest, wait for candidate readiness, run the
deployment smoke against the candidate and the private HTTPS boundary, and record an
external JSON evidence file that holds no credentials or personal data.
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

Catalogue synchronization is one internal management-port command, never a public
request or scheduled job. PostgreSQL enforces one active run and fences an abandoned
worker before a successor can write; run history is retained in bounded quantity.
[ADR-0017](../../decisions/0017-discover-catalogue-members-automatically-from-igdb.md)
owns the date interval, paging and partial-failure decisions.

| Failure                       | Required behaviour                                                                                           |
|-------------------------------|--------------------------------------------------------------------------------------------------------------|
| IGDB unavailable/rate-limited | Continue local reads; sync records failure                                                                   |
| Cover CDN unavailable         | Use product fallback; keep game visible                                                                      |
| No valid catalogue            | `CATALOGUE_NOT_READY`; no request-path provider call                                                         |
| Migration failure             | Do not activate new application                                                                              |
| Readiness/smoke failure       | Record deployment failure; recover by the assessed rollback or forward fix, never by reverting an applied migration |
| Backup failure                | Report recoverability failure; do not claim release success                                                  |
| Host loss                     | Rebuild the foundation, restore durable state from an encrypted backup, then redeploy and repeat the smoke   |

Standard OCI images, PostgreSQL logical backups, OpenTelemetry and private ingress
preserve portability. Hosting reconsideration triggers belong to ADR-0019.
