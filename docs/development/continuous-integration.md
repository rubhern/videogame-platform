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
| Real OIDC/BFF session | `bash scripts/validate-identity.sh` |
| OCI image | `bash scripts/validate-container-image.sh` |
| IGDB PoC fixtures | `./mvnw -f tools/igdb-poc/pom.xml clean verify` |

Commands and exact tool/action versions live in package manifests, Maven POMs,
scripts, Dockerfile, and `.github/workflows/`. CI uses no live IGDB credentials and
does not provision or deploy remote infrastructure.

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
