# Walking-skeleton continuous integration

- **Status:** Active
- **Last updated:** 2026-08-23
- **Runtimes:** Eclipse Temurin Java 25, Node.js 24, PostgreSQL 18, Docker Buildx and QEMU
- **Workflows:** [`build-and-verify.yml`](../../.github/workflows/build-and-verify.yml),
  [`security.yml`](../../.github/workflows/security.yml), and
  [`dependency-submission.yml`](../../.github/workflows/dependency-submission.yml)
- **Delivery rules:** [Learning MVP delivery lifecycle](delivery-lifecycle.md)

## Purpose and boundary

Pull requests targeting `main` run the smallest complete quality and security set
selected from their changed areas. Every trusted push to `main` deliberately enables
the complete integration suite, builds and validates the multi-architecture image,
and publishes the exact validated OCI index to GHCR. No workflow deploys
infrastructure, uses provider credentials, or calls IGDB.

The quality and security workflows expose one stable aggregate result each:
`Required quality gate` and `Required security gate`. Each always runs. It requires
every applicable job to finish with `success`, requires every inapplicable job to be
`skipped`, and fails on failure, cancellation, unexpected execution, or an unexpected
skip. These are the only two check names that need to be stable for repository rules.

## Change detection and fail-safe behaviour

Both workflows invoke [`detect-ci-changes.sh`](../../scripts/detect-ci-changes.sh)
with the event's complete base/head range. Pull requests use
`pull_request.base.sha` and `pull_request.head.sha`; pushes use `event.before` and
`github.sha`. The script disables rename detection so a rename is deliberately
classified as both a deletion and an addition, preserving the impact of the old and
new paths. It also covers ordinary added, modified, and deleted paths.

One centralized mapping emits independent `documentation`, `openapi`, `frontend`,
`browser`, `backend`, `migrations`, `identity`, `provider_fixtures`, `container`,
`build`, `ci`, `dependencies`, `npm_dependencies`, `sonar`, and language-specific
CodeQL outputs. A path
may enable several categories: OpenAPI enables documentation/contracts plus backend
and frontend contract consumers. CI/classifier changes and unknown paths enable all
categories rather than risking a false negative. `push main` passes `--full`, which
also enables all categories regardless of the changed paths.

[`test-ci-change-detection.sh`](../../scripts/test-ci-change-detection.sh) exercises
the required path scenarios, combined changes, full `main` selection, unknown-path
fail-safe behaviour, real Git ranges with additions/modifications/deletions/renames,
and aggregate-result handling. [`verify-ci-results.sh`](../../scripts/verify-ci-results.sh)
implements the stable final-gate semantics used by both workflows.

## Quality workflow

| Job | Pull-request applicability | Repository command or evidence |
|---|---|---|
| Detect affected CI areas | Always | Changed-range whitespace check, central classifier, and classifier tests when CI logic changes |
| Documentation and API contracts | Documentation, OpenAPI, build metadata, or CI | `validate-docs.sh`; actionlint only for CI changes; npm/OpenAPI/Redoc only for OpenAPI consumers |
| Frontend static, type, component and build checks | Frontend, OpenAPI, or relevant packaging/build input | `npm ci --ignore-scripts`, generated-type diff, and `npm run frontend:verify` |
| Packaged application Chromium smoke and accessibility check | UI/browser behaviour, identity, or shared packaging/runtime inputs | `validate-browser.sh` with digest-pinned Java and Playwright images |
| Real Keycloak 26.7 OIDC BFF compatibility | Identity/OIDC/BFF/session/CSRF or shared runtime inputs | `validate-identity.sh` with real Keycloak and PostgreSQL |
| Multi-architecture application image | Dockerfile, image/packaging scripts, resource-budget check, or shared container inputs | Dependency ARM64 manifest checks; `validate-topology-budget.sh`; `validate-container-image.sh` with Buildx, QEMU, runtime checks, Trivy, and CycloneDX |
| Java backend, architecture and PostgreSQL integration | Backend, OpenAPI backend consumer, persistence, identity, container, or shared Maven/build input | `./mvnw clean verify` plus retained JaCoCo report |
| Fresh PostgreSQL 18 migration | Flyway, schema, PostgreSQL initialization, persistence, or shared Maven input | `validate-migrations.sh` |
| IGDB PoC local fixtures | `tools/igdb-poc/**` or fail-safe broad selection | Isolated Maven `clean verify` with local fixtures |
| SonarQube Cloud quality gate | Backend/source/build/CI changes when trust and plan permit | Maven verify, JaCoCo import, and the pinned scanner |
| Required quality gate | Always | Strict comparison of classifier applicability with every job result |

Every row is enabled on trusted `main`, independently of the pull-request
applicability column.

The backend build receives `GITHUB_SHA` as its safe source revision. Hosted runners
already provide Docker, so Testcontainers creates a new `postgres:18.4-bookworm`
container and database for each backend or migration job.
The same Maven build runs OpenAPI Generator in `generate-sources`; generated Java is
ignored disposable output, and any incompatible manual controller fails compilation.

The image job registers only ARM64 QEMU emulation on the AMD64 hosted runner and
uses Buildx for `linux/amd64,linux/arm64`. Before the application build, it verifies
that the exact PostgreSQL and Keycloak images expose both supported manifests and
that the rendered complete Compose topology remains within 2 OCPU and 12 GB. Its
result is part of `Required quality gate`. On trusted `main` only, the publication
job waits for both the image job and the complete aggregate quality result, downloads
the exact validated OCI archive, verifies its checksum, and copies it to
`ghcr.io/rubhern/videogame-platform:<full-commit-sha>` with Skopeo
`--preserve-digests`. It rejects a remote digest mismatch or missing architecture and
never creates `latest`.

Spotless Maven 3.9.0 uses the explicitly pinned google-java-format 1.36.1 AOSP style,
removes unused imports, and rejects wildcard imports. Its `check` goal is bound to
`verify`; `./mvnw spotless:apply` is the deliberate local repair command. JaCoCo
0.8.15 instruments the tests and binds report generation to `verify`. It emits
`backend/target/site/jacoco/jacoco.xml` for tools and
`backend/target/site/jacoco/index.html` for people. CI retains the complete report
directory for seven days.

There is deliberately no `jacoco:check` execution or project-wide percentage in the
Maven build. Coverage is evidence for missing tests, not a substitute for the
domain, contract, authorization, persistence and journey assertions that protect
behaviour. Sonar way remains the centrally visible quality gate and may apply its
own conditions to new code; this project does not add a second arbitrary global
threshold.

## SonarQube Cloud integration and plan behaviour

The CI-based integration uses organization `rubhern`, project key
`rubhern_videogame-platform`, the EU endpoint `https://sonarcloud.io`, and the
repository secret `SONAR_TOKEN`. Automatic Analysis stays disabled so one analysis
is not duplicated by SonarQube Cloud and GitHub Actions. The Maven scanner is fixed
to 5.7.0.6970, runs on the approved Java 25 JDK, includes non-JVM repository sources,
and excludes generated OpenAPI/API-reference and disposable build output.

The scanner waits up to 300 seconds for the Sonar way result. A failed gate or an
analysis/timeout error fails `SonarQube Cloud quality gate`, which is consumed by the
stable `Required quality gate` result.

Plan and trust behaviour is explicit:

- every trusted push to `main` is analyzed and must pass Sonar way, independently of
  the PR-plan variable;
- OSS and Team plans can analyze a PR before merge. For those plans, repository
  variable `SONAR_PR_ANALYSIS_ENABLED=true` enables the scan for same-repository,
  non-Dependabot PRs and Sonar applies the PR/new-code conditions;
- the Free plan does not provide pre-merge PR analysis. Set
  `SONAR_PR_ANALYSIS_ENABLED=false`; PRs still run all applicable quality and security
  gates, while the next trusted `main` run performs the complete Sonar analysis;
- fork and Dependabot PRs never receive `SONAR_TOKEN`, so Sonar is explicitly not
  applicable there. Their other applicable gates remain required and `main` is
  analyzed after merge.

The repository is public and is configured with the variable set to `true` for its
intended OSS-plan behaviour. If SonarQube Cloud reports that the organization uses
Free rather than OSS/Team, change only that variable to `false`; do not weaken the
`main` gate or expose the token to untrusted code.

## Security workflow

| Control | Pull-request applicability | Failure policy |
|---|---|---|
| Gitleaks 8.30.1 | Always, including documentation-only changes; scans complete committed history | Any detected secret fails the job; rotate a real exposed credential before repository cleanup |
| Dependency review | Any dependency manifest/lock, Dependabot/workflow dependency configuration, or fail-safe broad selection | Any newly introduced `high`/`critical` advisory fails; snapshot warnings are not retried and `warn-only` is disabled |
| npm dependency audit | npm manifests/lock, npm tooling configuration, or fail-safe broad selection | Any current high-severity npm advisory fails after a locked install without lifecycle scripts |
| CodeQL Java/Kotlin | Affected backend/provider Java or cross-cutting build/CI changes | Any action, build, or upload failure fails; findings are reported through GitHub code scanning |
| CodeQL JavaScript/TypeScript | Affected frontend/OpenAPI JavaScript tooling or cross-cutting build/CI changes | Any action or upload failure fails; findings are reported through GitHub code scanning |
| Dependabot | Checks npm, the backend Maven reactor, the isolated IGDB PoC and pinned GitHub Actions weekly | Updates create reviewable pull requests and must pass the same gates; they are never merged automatically |
| Maven dependency submission | Resolves the backend reactor and isolated IGDB PoC graph after relevant `main` changes and submits both under distinct correlators | A failed submission is visible as a failed workflow; no PR or untrusted code receives write permission |

All security controls, including both CodeQL languages, dependency review, and npm
audit, run on trusted `main`. `Secret scan` remains unconditional on both event types.

`dependency-submission.yml` is the repository-owned automatic Maven submission. It
uses the committed wrapper, Java 25 and the maintained GitHub submission action so
the two Maven entry points and immutable action revision are reviewable in source.
It writes to the same Dependency Submission API as GitHub's repository-setting
automation. Do not enable the separate GitHub-managed automatic-submission toggle at
the same time: that would resolve and upload an additional, redundant Maven snapshot.

Dependency review evaluates changes rather than silently converting an advisory into
success. `npm audit --audit-level=high` reports no known vulnerability in the locked
graph as of 2026-08-13; the compatible transitive parser fix applied with issue #24
is recorded in the [frontend README](../../frontend/README.md). A change that
introduces a new high-severity vulnerability is blocked.

## Permissions and untrusted code

- Quality defaults to no token permissions. Only jobs that check out source receive
  `contents: read`, including the aggregate gate's read-only verifier checkout. The
  trusted `main` publication
  job alone adds `packages: write`, uses `GITHUB_TOKEN`, and does not run for pull
  requests.
- Security defaults to no token permissions. Gitleaks and dependency review receive
  only `contents: read`; CodeQL additionally receives `actions: read` and
  `security-events: write` so it can publish analysis.
- Sonar receives only `contents: read`; its authentication is the scoped
  `SONAR_TOKEN` environment secret and is unavailable to untrusted PRs.
- Maven dependency submission is the only job with `contents: write`, because the
  Dependency Submission API requires it. It runs only after relevant trusted `main`
  changes or an explicit manual dispatch and never persists the workflow token in
  Git configuration.
- Checkout never persists the workflow token into Git configuration.
- Workflows use `pull_request`, never `pull_request_target`, so untrusted PR code
  does not execute with a trusted-base token or repository secrets.
- All reusable actions are pinned to full immutable commit SHAs with their reviewed
  release version in a comment. Dependabot watches those pins.
- Every job has a bounded `timeout-minutes`. Quality and security use separate
  concurrency groups; superseded PR runs are cancelled while trusted required
  `main` runs are preserved. A newer dependency snapshot cancels an older snapshot
  run because only the latest default-branch graph is authoritative.

## Dependency caches and build output

`setup-node` caches npm's download cache using `package-lock.json`; it does not cache
`node_modules` or `frontend/dist`. `setup-java` caches the Maven local dependency
repository using the applicable POM files; it does not cache `target` directories or
packaged jars. Every job therefore rebuilds disposable output from the reviewed
source and lockfiles. All CI installations use `npm ci --ignore-scripts`: the
repository's checks do not require dependency lifecycle hooks, so suppressing them
reduces the execution surface of an untrusted dependency graph.

The browser job retains its Playwright HTML diagnostics and the backend job retains
JaCoCo XML/HTML for seven days. The image job retains inspection, runtime, Trivy, and
CycloneDX evidence for seven days. Trusted `main` additionally transfers the exact
validated OCI archive for one day so the publication job does not rebuild it and
retains the published SHA/digest/platform record for 30 days. Those artifacts cannot
change a completed validation result.

## Failure and retry policy

There is no `continue-on-error`, unconditional rerun, or test retry in either
workflow. Playwright sets `retries: 0` in all environments and retains a trace from
the original failed attempt. The dependency review action also disables snapshot
retries. A person may manually rerun a workflow to diagnose runner infrastructure,
but the original failed check remains evidence and flaky behaviour must be fixed or
handled through the lifecycle's explicit temporary-waiver process.

## Opt-in complete local parity

The delivery lifecycle requires risk-based, incremental local validation and treats
pull-request CI as the authoritative affected-area result and trusted `main` CI as
the complete integration result. The following parity sequence is therefore not the
routine local completion check. Use it only when a cross-cutting or high-risk change,
insufficient CI evidence, critical release, local-only failure, or explicit owner
request justifies full local coverage. Otherwise run only the commands related to the
affected boundary and let CI execute its event-appropriate set.

When full local parity is justified, first run the workstation prerequisite gate,
then use the repository commands also executed by CI:

```bash
bash scripts/validate-prerequisites.sh
bash scripts/validate-actions.sh
git diff --check
npm ci
bash scripts/validate-docs.sh
bash scripts/validate-openapi.sh
npm run build:openapi-docs
git diff --exit-code -- docs/architecture/api/reference/index.html
npm run frontend:generate-api
git diff --exit-code -- frontend/src/shared/api/generated/schema.d.ts
npm run frontend:verify
bash scripts/validate-browser.sh
bash scripts/validate-identity.sh
bash scripts/local-dependencies.sh verify-images
bash scripts/validate-topology-budget.sh
bash scripts/validate-container-image.sh
bash scripts/validate-migrations.sh
./mvnw clean verify
./mvnw -f tools/igdb-poc/pom.xml clean verify
```

`validate-actions.sh` downloads actionlint 1.7.12 for Linux AMD64 or ARM64, verifies
the vendor SHA-256 before extraction, and checks every committed workflow. The CI
whitespace step additionally runs `git diff --check` over the actual PR base/head or
`main` push range; the local command checks unstaged changes. This makes both checks
real CI gates rather than documentation-only recommendations.

After Maven verification, open the local human-readable coverage report at
`backend/target/site/jacoco/index.html`. An authenticated manual Sonar diagnostic
uses the same pinned scanner and report:

```bash
read -rsp 'Sonar token: ' SONAR_TOKEN
export SONAR_TOKEN
./mvnw \
  -Dsonar.qualitygate.wait=true \
  -Dsonar.qualitygate.timeout=300 \
  clean verify \
  org.sonarsource.scanner.maven:sonar-maven-plugin:5.7.0.6970:sonar
unset SONAR_TOKEN
```

Never place the token in a file, command history, pull-request log, or Maven
configuration. Normal local verification does not require Sonar credentials.

The browser wrappers run the same official Playwright 1.62.1 Noble and Eclipse
Temurin 25 runtime images by immutable digest in local and CI environments. It builds
the Vite assets and combined Spring Boot JAR, then starts that artifact and a fresh
PostgreSQL 18 database on an isolated disposable Docker network. Chromium consumes
the real same-origin response; no request interception or provider credential is
used, and the internal network prevents IGDB egress. The wrapper removes its exact
containers and network on exit.

`validate-identity.sh` also starts real Keycloak 26.7 from the existing realm import,
uses a fresh isolated PostgreSQL database and random ephemeral credentials, and
starts the packaged application with the `oidc` profile. Its focused Chromium test
does not intercept OIDC or product requests. The script never prints credentials,
disables retries and traces, and removes its exact containers and internal network.

Gitleaks, dependency review, CodeQL and dependency submission are GitHub-context
controls: their complete range comparison, token permissions and service upload
cannot be reproduced by the application verification commands. Local Gitleaks may
be used as an additional pre-push diagnostic, but it does not replace the workflow
result.

## Semantic Versioning assessment

Issue #24 changes delivery automation, formatting enforcement, coverage reporting,
code-quality analysis, dependency monitoring, test retry behaviour, documentation,
and a compatible transitive development-tool lock. Spotless establishes one
format-only baseline but does not alter runtime semantics; JaCoCo instruments tests,
not the packaged application. The change does not alter the backend jar's supported
behaviour, frontend production assets or isolated IGDB PoC behaviour. Their versions
therefore remained `0.2.0-SNAPSHOT`, `0.0.1` and `0.1.0-SNAPSHOT` respectively for
that change. Issue #25 later increments the backend to `0.3.0-SNAPSHOT`; its
contract-first generator adoption increments the compatible OpenAPI metadata from
`1.0.0` to `1.0.1` without changing the wire operations. The private root tooling
package remains `1.0.0`; it is not a published runtime artefact. The subsequent
composition-root correction increments only the backend patch to `0.3.1-SNAPSHOT`.
The subsequent PostgreSQL JDBC security remediation increments the backend patch to
`0.3.2-SNAPSHOT`; it leaves the OpenAPI, frontend, root tooling package and isolated
IGDB PoC versions unchanged. The later release-query builder refactor increments only
the backend patch to `0.3.3-SNAPSHOT`. Issue #26 adds compatible frontend product
behaviour and combined application packaging, so the frontend advances to `0.1.0`
and the backend reactor to `0.4.0-SNAPSHOT`; the unchanged OpenAPI and tooling
package versions remain as-is. A later runtime or product change must assess those
artefacts independently under the delivery lifecycle.

Issue #40 adds a compatible BFF identity capability and session logout operation.
The backend reactor therefore advances to `0.5.0-SNAPSHOT` and OpenAPI to `1.2.0`.
The frontend remains `0.1.0`; its product runtime is unchanged. Root tooling and the
isolated IGDB PoC also remain unchanged.

Issue #27 adds a compatible production delivery format for the combined application.
The backend reactor advances to `0.6.0-SNAPSHOT` because its executable JAR is now
the versioned image payload. The image scan also advances the Spring-managed Log4j
and Jackson lines to compatible security patches without changing their approved
minor baselines. Frontend source and behaviour, OpenAPI, root tooling, and the
isolated IGDB PoC are unchanged, so their versions remain unchanged.

Selective pull-request routing changes delivery automation and evidence placement,
not the supported behaviour of the backend JAR, frontend assets, OpenAPI contract, or
isolated IGDB PoC. This change therefore does not increment those executable artefact
versions or the private root tooling package.

Issue #34 adds an executable assertion over existing Compose CPU/memory limits and
runs the existing PostgreSQL/Keycloak manifest proof in the image job. It changes
validation and documentation only, not backend, frontend, OpenAPI, root npm tooling,
or IGDB PoC runtime behaviour; their versions remain unchanged.

## Remaining delivery work

- Pull requests and trusted `main` runs retain the GitHub-hosted execution evidence;
  a local pass never replaces those required results.
- Repository rules should require the two stable aggregate checks after their first
  successful run; configuring merge policy is repository administration, not
  executable workflow source.
- Trusted `main` run `32661542668` confirmed the GHCR publication, preserved digest,
  both application platforms and retained evidence for commit `e138f54`; later runs
  must preserve the same controls.
- Deployment, infrastructure provenance, remote smoke, and capacity evidence remain
  later work. The initial compatibility resource budget, Trivy, and CycloneDX image
  evidence are implemented here without
  replacing CodeQL, compiler lint, ArchUnit, Spring Modulith, ESLint, Spotless,
  JaCoCo, or SonarQube Cloud.
