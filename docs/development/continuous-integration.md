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

| Area | Main local entry point |
|---|---|
| Documentation | `bash scripts/validate-docs.sh` |
| OpenAPI | `bash scripts/validate-openapi.sh` |
| Workflow syntax/classification | `bash scripts/validate-actions.sh` and `bash scripts/test-ci-change-detection.sh` |
| Frontend | `npm run frontend:verify` |
| Backend | `./mvnw clean verify` |
| Migrations | `bash scripts/validate-migrations.sh` |
| Packaged browser | `bash scripts/validate-browser.sh` |
| Real OIDC/BFF session and Keycloak rating journey | `bash scripts/validate-identity.sh` |
| Private-dev runtime/deployment | `bash scripts/validate-private-dev-runtime.sh`, then `bash scripts/validate-private-dev-runtime.sh --telemetry-smoke` and `python3 scripts/private-dev-logs-check.py --smoke` |
| Local translation lifecycle/topology | `bash scripts/test-local-localization.sh`; optional `--smoke` with an installed model |
| OCI image | `bash scripts/validate-container-image.sh` |
| IGDB PoC fixtures | `./mvnw -f tools/igdb-poc/pom.xml clean verify` |

Commands and exact tool/action versions live in package manifests, Maven POMs,
scripts, Dockerfile, and `.github/workflows/`. Validation CI uses no live IGDB or
deployment credentials and does not provision or deploy remote infrastructure.
The separate [owner-approved promotion workflow](../../.github/workflows/deploy-private-dev.yml)
derives the validated image from the exact dispatch SHA and uses dev environment
credentials only after the owner dispatch (the sole human deployment approval); its policy and
runtime boundary belong to the [platform design](../architecture/deployment/mvp-platform-and-delivery.md#artefact-and-delivery).
Private-dev validation uses
disposable configuration, a fake deployment boundary to prove ordering/lock/failure
semantics, and an in-memory Keycloak-admin double to prove smoke-account ownership
rules; only the owner-run host commands can provision the real account or perform a
real deployment. Offline promotion regressions additionally cover CI trust,
publication binding, restricted commands, runtime drift and sanitized failure evidence.

Trusted `main` builds validate and publish the same non-root multi-architecture OCI
index by immutable commit SHA/digest. Pull requests never publish. Image scanning,
SBOM generation, dependency submission, CodeQL, and Sonar configuration remain
defined by their workflows rather than duplicated here.

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
