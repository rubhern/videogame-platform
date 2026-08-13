# Walking-skeleton continuous integration

- **Status:** Active
- **Last updated:** 2026-08-13
- **Runtimes:** Eclipse Temurin Java 25, Node.js 24, PostgreSQL 18 through Testcontainers
- **Workflows:** [`quality-gates.yml`](../../.github/workflows/quality-gates.yml),
  [`security.yml`](../../.github/workflows/security.yml), and
  [`dependency-submission.yml`](../../.github/workflows/dependency-submission.yml)
- **Delivery rules:** [Learning MVP delivery lifecycle](delivery-lifecycle.md)

## Purpose and boundary

The walking-skeleton workflows reproduce the current repository evidence on every
pull request targeting `main` and every trusted push to `main`. They do not publish
an OCI image, deploy infrastructure, use provider credentials, or call IGDB. Image
assembly, multi-architecture scanning and GHCR publication belong to issue #27.

The quality and security workflows expose one stable aggregate result each:
`Required quality gate` and `Required security gate`. Either aggregate fails when any required job fails, is
cancelled, or is skipped, so a later diagnostic step cannot hide an incomplete gate.

## Quality workflow

| Job | Repository command or evidence | Purpose |
|---|---|---|
| Documentation and API contracts | changed-range `git diff --check`, `validate-actions.sh`, `validate-docs.sh`, `validate-openapi.sh`, generated Redoc diff | Reject whitespace errors, invalid workflow syntax or expressions, broken links, malformed sources, contract errors, or stale API documentation |
| Frontend static, type, component and build checks | `npm ci`, OpenAPI type generation diff, `npm run frontend:verify` | Prove the locked dependency graph, ESLint, strict `tsc --noEmit`, Vitest and the production Vite build |
| Frontend Chromium smoke and accessibility check | `validate-browser.sh` with the digest-pinned Playwright image | Exercise the production preview, keyboard navigation and the axe-core accessibility baseline in a real browser |
| Java backend, architecture and PostgreSQL integration | `./mvnw clean verify` plus retained JaCoCo report | Enforce Spotless, compile/package with Java 25, run unit and startup checks, Spring Modulith verification, ArchUnit rules and PostgreSQL 18 integration tests, and produce XML/HTML coverage evidence |
| Fresh PostgreSQL 18 migration | `validate-migrations.sh` | Independently create an empty database, apply Flyway from zero and verify schema, seed and runtime-privilege guarantees |
| IGDB PoC local fixtures | targeted Maven `clean verify` | Preserve the provider decision evidence without credentials or live provider traffic |
| SonarQube Cloud quality gate | Maven verify, JaCoCo XML import and SonarScanner for Maven | Analyze Java 25 and repository non-JVM sources, then wait for the configured Sonar way result when analysis is applicable |

The backend build receives `GITHUB_SHA` as its safe source revision. Hosted runners
already provide Docker, so Testcontainers creates a new `postgres:18.4-bookworm`
container and database for each backend or migration job.

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
  `SONAR_PR_ANALYSIS_ENABLED=false`; PRs still run all local quality and security
  gates, while the next trusted `main` run performs the Sonar analysis;
- fork and Dependabot PRs never receive `SONAR_TOKEN`, so Sonar is explicitly not
  applicable there. Their local gates remain required and `main` is analyzed after
  merge.

The repository is public and is configured with the variable set to `true` for its
intended OSS-plan behaviour. If SonarQube Cloud reports that the organization uses
Free rather than OSS/Team, change only that variable to `false`; do not weaken the
`main` gate or expose the token to untrusted code.

## Security workflow

| Control | Behaviour | Failure policy |
|---|---|---|
| Gitleaks 8.30.1 | Scans complete committed history with the pinned Gitleaks action; PR comments and finding artifacts are disabled to keep permissions read-only and findings out of artifacts | Any detected secret fails the job; rotate a real exposed credential before repository cleanup |
| Dependency audit and review | Audits the complete npm lock graph, then compares the PR base/head or the previous/current `main` revisions across supported ecosystems | Any current or newly introduced `high`/`critical` advisory fails; snapshot warnings are not retried and `warn-only` is disabled |
| CodeQL | Runs extended security queries for Java/Kotlin and JavaScript/TypeScript | Any action or upload failure fails; findings are reported through GitHub code scanning |
| Dependabot | Checks npm, the backend Maven reactor, the isolated IGDB PoC and pinned GitHub Actions weekly | Updates create reviewable pull requests and must pass the same gates; they are never merged automatically |
| Maven dependency submission | Resolves the backend reactor and isolated IGDB PoC graph after relevant `main` changes and submits both under distinct correlators | A failed submission is visible as a failed workflow; no PR or untrusted code receives write permission |

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
  `contents: read`; the aggregate gate receives none.
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
source and lockfiles.

The browser job retains its Playwright HTML diagnostics and the backend job retains
JaCoCo XML/HTML for seven days. Those artifacts are never consumed as build input and
cannot change the test result.

## Failure and retry policy

There is no `continue-on-error`, unconditional rerun, or test retry in either
workflow. Playwright sets `retries: 0` in all environments and retains a trace from
the original failed attempt. The dependency review action also disables snapshot
retries. A person may manually rerun a workflow to diagnose runner infrastructure,
but the original failed check remains evidence and flaky behaviour must be fixed or
handled through the lifecycle's explicit temporary-waiver process.

## Equivalent local verification

First run the workstation prerequisite gate. Then use these repository commands,
which are the same commands executed by CI:

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

The browser wrapper runs the same official Playwright 1.62.1 Noble image by immutable
digest in local and CI environments. The image provides Node 24, Chromium and its
native libraries; the container runs as the current user with external network access
disabled and consumes the lockfile-installed workspace from the preceding `npm ci`.

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
therefore remain `0.2.0-SNAPSHOT`, `0.0.1` and `0.1.0-SNAPSHOT` respectively. The
private root tooling package also remains `1.0.0`; it is not a published runtime
artefact. A later runtime or product change must assess those artefacts independently
under the delivery lifecycle.

## Remaining delivery work

- Pull requests and trusted `main` runs retain the GitHub-hosted execution evidence;
  a local pass never replaces those required results.
- Repository rules should require the two stable aggregate checks after their first
  successful run; configuring merge policy is repository administration, not
  executable workflow source.
- Trivy, CycloneDX and OCI provenance remain approved incremental controls for the
  image-owning issue. Spotless, JaCoCo and SonarQube Cloud are implemented here
  alongside CodeQL, compiler lint, ArchUnit, Spring Modulith and ESLint.
