# Continuous integration

GitHub Actions runs selective affected-area gates on pull requests and the complete
integration suite on trusted `main`. The workflows and
[`detect-ci-changes.sh`](../../scripts/detect-ci-changes.sh) are the executable source
for job selection; this document explains the stable contract.

## Stable gates

- `Required quality gate`
- `Required security gate`

Each aggregate gate always runs. It fails when an applicable job fails, is cancelled,
or is skipped unexpectedly, and also when an inapplicable job runs unexpectedly.
Unknown paths and changes to shared CI/classification logic select broad validation.
Secret scanning remains applicable to every pull request.
Changes to Flyway migration or development seed SQL also select both runtime
gates because their disposable observability databases load those sources.

Maven's [resolver configuration](../../.mvn/maven.config) bounds recovery from
temporary artifact-server throttling. Its HTTP transport retries the failed
download request while the same build invocation continues. Compilation, tests
and Sonar qualification retain their normal failure semantics.
Packaging, fresh migrations, Java CodeQL, and Sonar use a
[resolution retry](../../scripts/maven-with-resolution-retry.sh) when Maven Central
reports that a parent POM or build plugin is missing.

### Environment admission

Trusted `main` publishes the verified application image after the source secret
scan and the application build, runtime and image checks. The complete integration
and security suites continue in parallel. Private-dev can proceed once its own
checks have passed for that SHA and digest. The selection includes the source
checks, the published image, and affected deployment risks such as OpenAPI, real
PostgreSQL migrations, identity and private-dev runtime. It covers the entire
change from the last proven private-dev deployment. A failed, cancelled or
unexpectedly skipped required check refuses promotion. CI and unclassified
changes, an unavailable baseline, or the owner's full-CI fallback require the
complete gates. The [private-dev operator guide](../../deploy/private-dev/README.md#promote-and-verify)
explains how to use the fallback.

Production qualification requires the complete successful trusted-main quality
and security runs and current publication evidence for the application and all
production dependency images of the selected SHA. A release-only failure may
leave private-dev eligible. The production verifier always requires its own
evidence and verifies it again before the protected host and recovery steps. See
the [candidate procedure](../../deploy/public-production/README.md#owner-approved-actions-deployment-467)
and the [delivery lifecycle](delivery-lifecycle.md#production-upgrade-compatibility).

## Validation commands

| Area | Main local entry point |
|---|---|
| Documentation | `bash scripts/validate-docs.sh` |
| Documentation portal | `npm --prefix documentation run validate` and `bash documentation/scripts/test-container.sh` |
| OpenAPI | `bash scripts/validate-openapi.sh` |
| Workflow syntax/classification | `bash scripts/validate-actions.sh` and `bash scripts/test-ci-change-detection.sh` |
| Frontend | `npm run frontend:verify` |
| Backend | `./mvnw clean verify` |
| Migrations | `bash scripts/validate-migrations.sh`; shared role bootstrap without password arguments or log exposure: `python3 -I scripts/test-postgres-bootstrap.py` |
| Packaged browser | `bash scripts/validate-browser.sh` |
| Real OIDC/BFF session and Keycloak rating journey | `bash scripts/validate-identity.sh` |
| Public-production realm policy (disposable Keycloak and Mailpit) | `bash scripts/validate-production-identity.sh`; `--inspect` serves the same stack on loopback for a manual browser check |
| Google and Microsoft sign-in (ADR-0045): reviewed first-broker-login flow, terms for new provider accounts, password-only linking, unverified Microsoft email, cancellation, disabling (disposable Keycloak with OIDC stand-ins and Mailpit), and the actor's and host tool's offline contracts | `bash scripts/validate-social-sign-in.sh`; offline only: `python3 -I scripts/test-production-social-sign-in.py` (also in the selective production-runtime gate) |
| Private-dev identity alignment, rollback and Mailpit/relay transports (disposable Keycloak, Mailpit and an authenticated STARTTLS relay stand-in) | `bash scripts/validate-private-dev-identity.sh` |
| Personal-data export, erasure, deletion ledger and restore replay (fake runtime, then disposable Keycloak, PostgreSQL and the packaged BFF) | `bash scripts/validate-personal-data.sh`; focused native-read/streaming seam: `python3 -I scripts/test-personal-data-read-model.py`; GPG/privacy lifecycle including legacy ledger faults: `python3 -I scripts/test-personal-data-privacy.py` |
| Production backup/recovery refusals and real public-key encryption | `python3 scripts/test-production-recovery.py` and `python3 scripts/test-production-recovery-lock.py` (part of the selective production-runtime gate); the trusted-publication host-loss rehearsal stays local |
| Production identity-maintenance fence and drain (simulated runtime, then a disposable Keycloak) | `python3 scripts/test-production-identity-maintenance.py` and `python3 scripts/test-production-cleanup.py` (part of the selective production-runtime gate); trusted-image cleanup/telemetry rehearsal stays local |
| Edge limiter memory under bounded source churn (local measurement, not a gate) | `python3 scripts/measure-edge-limiter-memory.py --family 4\|6 --sources N --rate R --evidence DIR` |
| Production telemetry, dashboard parity, privacy and notifications | `python3 scripts/test-production-observability.py --smoke` in the selective production-runtime gate; exact PromQL/timing, canonical dashboard and SQL queries, missing data, HTTPS Telegram mock and sanitized overhead receipt. Backend export-failure isolation is covered by `TelemetryFailureIsolationIntegrationTest` |
| Public-production runtime contract (rendered Compose, adapted Caddyfile, disposable edge built from its recipe with upstream stand-ins: routes, per-source and aggregate limits, spoofing, IPv4/IPv6 sources, maintenance gate and deliberate violations) | `python3 -I scripts/test-public-production-runtime.py`; `--env-file <protected runtime.env>` checks a real host's inputs statically |
| Public-production manual deployment, integration refusals and isolation | `python3 scripts/test-public-production-deployment.py` and `python3 scripts/test-public-production-integration.py`; the [disposable runtime exercise](../../deploy/public-production/README.md#disposable-integration-exercise) consumes retained trusted-main images locally |
| Private first-install preparation, trust/permission/isolation refusals, ordered bootstrap, crash fencing, the post-acceptance restart transition and the in-place upgrade | `python3 scripts/test-private-production-preparation.py`, `python3 scripts/test-private-production-installation.py`, `python3 scripts/test-private-production-control.py`, `python3 scripts/test-private-production-acceptance.py`, `python3 scripts/test-private-production-upgrade.py` and the [upgrade compatibility](delivery-lifecycle.md#production-upgrade-compatibility) proof `python3 scripts/test-private-production-upgrade-compatibility.py` (it renders the accepted first installation and the pull request's base or previous `main`, passed as `PRODUCTION_UPGRADE_BASELINES`) in the selective production-runtime gate; simulated host/storage checks use no VPS or real secret. Real disposable Docker mechanisms are a separate manually invoked `python3 scripts/test-private-production-control-docker.py --evidence /new/protected/report.json` campaign, `python3 scripts/test-private-production-restart-docker.py --evidence /new/report.json` proves restart and upgrade-fence semantics on a disposable real daemon, and `python3 scripts/rehearse-private-production-upgrade.py --target-revision <sha> --evidence /new/directory` proves the upgrade on real disposable services; none is target-host qualification. [Host procedure](../../deploy/public-production/README.md#private-first-installation-and-validation) remains separately authorized |
| Private-dev runtime/deployment | `bash scripts/validate-private-dev-runtime.sh`, then `bash scripts/validate-private-dev-runtime.sh --telemetry-smoke`, `python3 deploy/private-dev/grafana/check-alerts.py --smoke` and `python3 scripts/private-dev-logs-check.py --smoke` |
| Private-dev optimized registration metrics and notification routing | `python3 deploy/private-dev/grafana/check-product-growth.py` and `python3 deploy/private-dev/grafana/check-notifications.py --smoke` |
| Workstation and private-dev translation lifecycle/topology | `bash scripts/test-local-localization.sh`; optional local `--smoke` with an installed model |
| OCI image | `bash scripts/validate-container-image.sh` |
| Production Keycloak, localizer and edge images (build, content, runtime boundary, added-findings scan policy, edge module policy) | `bash scripts/validate-dependency-image.sh keycloak`, `catalogue-localizer` or `caddy`; content, scan, module and Go advisory policy self-test: `python3 -I scripts/check-dependency-image-content.py --self-test` |
| IGDB PoC fixtures | `./mvnw -f tools/igdb-poc/pom.xml clean verify` |

Commands and exact tool/action versions live in package manifests, Maven POMs,
scripts, Dockerfile, and `.github/workflows/`. Validation CI uses no live IGDB or
deployment credentials and does not provision or deploy remote infrastructure.
The separate [owner-approved promotion workflow](../../.github/workflows/deploy-private-dev.yml)
derives the validated image from the exact dispatch SHA and uses dev environment
credentials only after the owner dispatch (the sole human deployment approval); its policy and
runtime boundary belong to the [platform design](../architecture/deployment/mvp-platform-and-delivery.md#artefact-and-delivery).
The [backup watch workflow](../../.github/workflows/backup-watch.yml) is not validation CI: it is an
[independent production actor](../../deploy/public-production/README.md#independent-supervision), inert until the owner enables it.
Private-dev validation uses
disposable configuration, a fake deployment boundary to prove ordering/lock/failure
semantics, and an in-memory Keycloak-admin double to prove smoke-account ownership
rules; only the owner-run host commands can provision the real account or perform a
real deployment. Offline promotion regressions additionally cover CI trust,
publication binding, restricted commands, runtime drift and sanitized failure evidence.

Trusted `main` builds validate and publish the same non-root AMD64 OCI
image by immutable commit SHA/digest. Pull requests never publish. Runtime and
scanner checks import the retained archive;
publication copies that archive with digest preservation and verifies the published
digest and architecture. [ADR-0026](../decisions/0026-defer-arm64-runtime-support.md)
owns deferred ARM64 support and its restoration gate. Image scanning,
SBOM generation, dependency submission, CodeQL, and Sonar configuration remain
defined by their workflows rather than duplicated here.

Portal validation uses the lockfile-selected Playwright Chromium with the hosted
Ubuntu runner's browser libraries and Latin/emoji fonts. Host dependency validation,
diagram rendering and the browser journeys under the production server remain
required; runner-image drift must fail visibly. A bare workstation still needs the
system dependency setup in the [portal workflow](../../documentation/README.md#local-workflow).

## Local parity

Do not run all gates routinely. Follow the risk-based selection policy in the
[delivery lifecycle](delivery-lifecycle.md). A full local sequence is justified only
for cross-cutting build/CI changes, a high-risk migration, unavailable CI, a critical
release, local reproduction, or an explicit owner request. When it is justified, the
table above is the parity catalogue; record why the broader evidence is needed.

Green remote checks are evidence only for the commit they tested. Update/rebase a
stale branch and use the new run instead of compensating with unrelated local suites.

Catalogue localization adds deterministic Python helper/operator boundary tests to the
backend gate and a private-helper image check/build to the container gate. CI never
downloads model weights or requires live IGDB credentials; native quality/resource
acceptance belongs to the [operations runbook](operations-runbook.md#catalogue-localization).

## Feedback measurements

[`measure-ci-latency.py`](../../scripts/measure-ci-latency.py) snapshots recent
successful first-attempt PR quality/security pairs and trusted-main pairs, including
job/step durations, workflow elapsed time and initial scheduling wait. Use a separate
output directory for each snapshot. Compare like affected-area workloads and cache
conditions; time to merge-ready is measured from the earliest paired workflow creation
to completion of both required gates, excluding publication. Scheduling wait includes
workflow orchestration and is not a precise per-job runner queue metric. Failed,
cancelled and rerun attempts are excluded from the successful-run distribution and
must be investigated separately. For delivery timing, launch the private-dev
workflow immediately after merge and record push, application publication,
private-dev verification, deployment receipt, and full release evidence times.
Track time waiting for dispatch separately and sum the job durations for runner
usage. Keep run links, timing evidence, cache limitations, risks and rollback in
the issue or PR.

Independent container/runtime checks may run in parallel; every applicable result
remains required. Production runtime operations, observability and edge exercises run
concurrently on one runner with separate disposable topologies; every branch result
is checked. The packaged browser suite is sharded over independent disposable
application/database stacks; the complete suite still runs locally by default. Every
matrix leg remains required, including cancellation and unexpected-skip handling.
The browser and applicable identity legs download the same JAR from their
workflow run. The producer runs Maven tests when the backend gate is inapplicable.
Each leg still builds its own disposable test topology. The identity gate runs its independent topologies as parallel legs
of one required check, each with its own disposable Keycloak, PostgreSQL and BFF, because
the timed journeys mutate realm and client state. Packaged browser/identity journeys may
omit duplicate Maven tests only when the separate backend gate is required for that
revision or another required leg of the same matrix retains them; otherwise exactly
one packaging leg of each matrix retains tests. Local packaging retains tests by
default. Cross-job build reuse must not
introduce stale artifacts, mix source revisions, expose trusted credentials to PRs, or
lengthen the critical path without measured benefit.


### Automatic account deletion preparation (#272)

The existing personal-data gate includes the automatically polled self-service journey with
synthetic accounts on real disposable Keycloak/PostgreSQL/BFF, runner restart after a
cross-system failure and pre-admission backup replay. Its `PERSONAL_DATA_COMPLETION_POLICY=private-dev`
variant proves verified automatic processing and interruption recovery without ledger/archive/GPG
configuration; CI runs both policies against separate disposable topologies. Its dedicated authenticated management
port is published only on loopback in that disposable topology. No real private-dev or
production configuration is changed. Temporary paired evidence tests the protocol, never
physical storage independence; production qualification remains in the operations runbook.

### Temporary build dependency exception

Owner-approved on 2026-11-17 for seven days, ending 2026-11-24 at 18:07:32 UTC.
The portal and frontend tooling audits temporarily accept only [GHSA-fc5q-39xc-vjpf](https://github.com/advisories/GHSA-fc5q-39xc-vjpf)
and findings inherited exclusively from it. The advisory affects picomatch through
4.0.7 and had no published fix when assessed. The owner, Ruben Hernandez, accepts
the remaining build-time denial-of-service risk during this window.

Compensating controls are repository-controlled build inputs, time-bounded CI,
loopback-only development serving, and a static runtime that copies only built
files. The deployed portal serves static files. The application frontend contains compiled browser assets.
A separate production-dependency audit must pass without the exception before
the frontend build-tooling audit runs. The owner extended the same acceptance
to frontend tooling on 2026-11-17 with the original deadline retained. Every other high
or critical finding continues to block the audit. Missing audit evidence and
expiry also block it. The audit helper owns the executable scope and deadline.

Remove this exception and restore direct npm audit when a patched version becomes
available and the lockfile has been updated. If the advisory disappears from the
audit, the helper reports that removal is due. Any renewal needs a new owner decision.
