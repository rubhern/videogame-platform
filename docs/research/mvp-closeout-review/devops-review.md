# MVP close-out DevOps review

- **Type:** Point-in-time delivery, CI/CD, automated-testing and release-engineering
  review (evidence, not an approved decision)
- **Reviewer:** AI-assisted review pass; the owner retains approval authority
- **Revision reviewed:** `d10bc44` on `main`, clean working tree (`#147` landed on
  `main` after this snapshot; it is a smoke fix and does not change the findings)
- **Scope:** GitHub Actions workflows, change detection and gate aggregation,
  caching and reproducibility, the testing strategy as a whole, Maven/npm/OpenAPI
  build, OCI image build, scanning, SBOM, publication and promotion, the approved
  `dev` deployment mechanism, release engineering, the developer workflow, and
  supply-chain controls.
- **Out of scope:** host, Tailscale, firewall, storage and capacity
  (`OUT_OF_SCOPE: INFRASTRUCTURE`, see the [infrastructure review](infrastructure-review.md));
  business metrics, logging strategy, spans and dashboards
  (`OUT_OF_SCOPE: OBSERVABILITY`); code and architecture (see the
  [code review](code-review.md) and the [architecture review](architecture-review.md)).
- **Sources contrasted:** `docs/development/delivery-lifecycle.md`,
  `docs/development/continuous-integration.md`,
  `docs/architecture/deployment/mvp-platform-and-delivery.md`, the technology
  baseline, the solution architecture, `.github/workflows/*.yml`,
  `.github/dependabot.yml`, `scripts/detect-ci-changes.sh`,
  `scripts/verify-ci-results.sh`, `scripts/test-ci-change-detection.sh`, every
  `scripts/validate-*.sh`, `scripts/package-application.sh`,
  `scripts/backend-artifact.sh`, `pom.xml`, `backend/pom.xml`, `package.json`,
  `frontend/package.json`, `frontend/playwright.config.ts`, `Dockerfile`,
  `.dockerignore`, `deploy/private-dev/compose.yaml` and
  `deploy/private-dev/bin/deploy-private-dev`.
- **Live evidence used:** the repository's branch-protection and ruleset state,
  repository variables, and job-level durations and conclusions of the last 25
  `build-and-verify` runs, all read through the GitHub API with read-only calls.
  No workflow was run and nothing was pushed for this document.

Nothing here changes workflows, code, tests, scripts or canonical documentation.

---

## 1. Executive summary

The path from a change to a validated, deployable artefact is in very good shape
for a single-operator MVP, and in several respects better than the norm: selective
affected-area gates on pull requests with a full run on `main`; two aggregate gates
that fail on unexpected skips *and* unexpected runs; SHA-pinned actions with
`permissions: {}` and `persist-credentials: false` everywhere; a digest-pinned
multi-architecture image built, run, scanned and SBOM'd once on `main` and then
copied byte-for-byte to GHCR with a digest equality check; a deployment command that
promotes only an immutable digest, verifies its OCI labels against the source
revision, migrates before replacing, and records evidence even on failure. A full
`main` run takes about eight minutes wall-clock and about twenty runner-minutes;
the backend Testcontainers suite completes in under two minutes.

What is missing is small and mostly about *enforcement and closure* rather than
mechanism:

1. **The two "required" gates are not required by GitHub** (`DR-01`, HIGH). `main`
   has no branch protection and no ruleset; the delivery lifecycle's "required
   checks must pass" and the CI guide's "stable gates" are conventions, not
   controls. Direct pushes have happened. Publication is still gated by the
   workflow, so no unvalidated image can reach GHCR, but history can be rewritten
   and a red merge is possible. This is a five-minute fix.
2. **Release engineering exists on paper only** (`DR-05`). No tag, no release, no
   non-`SNAPSHOT` image has ever existed; every published image carries
   `0.15.0-SNAPSHOT` in its version label. Closing the MVP needs the first named
   private release, and the lifecycle already says what it must record.
3. **Three validations sit in the wrong place or nowhere** (`DR-04`, `DR-06`,
   `DR-07`): the Postman collections are a contract artefact maintained "in the same
   change" but never executed; a backend change to the HTTP delivery layer does not
   select the packaged browser gate, which is exactly how the missing
   `/mis-puntuaciones` forward (`CR-02`) escaped; and the packaging script runs the
   entire backend test suite, so the browser and identity gates repeat it and a
   frontend-only pull request can fail on a backend integration test.
4. **The supply-chain story is stronger than its documentation admits in one place
   and weaker in another** (`DR-02`, `DR-03`): the platform design promises
   "SBOM/provenance evidence", but the build disables BuildKit provenance, the SBOM
   artefact expires after seven days and nothing is attached to the image; and the
   digest pins in Dockerfiles, Compose files and scripts have no automated update
   path because Dependabot's `docker` ecosystem is not configured.

Findings: **1 HIGH, 6 MEDIUM, 6 LOW**, plus five recorded revisit triggers. Five
GitHub issues are suggested.

Implementation state, to avoid reading design as delivery:

| State | What |
|---|---|
| **Implemented and exercised** | Selective PR gates, full `main` gates, aggregate gate semantics, image build/run/scan/SBOM, publication by SHA tag and digest, Dependabot for npm, Maven and Actions, Maven dependency-graph submission, Gitleaks, dependency review, npm audit, CodeQL (two languages), Sonar on same-repo PRs and `main` |
| **Approved, pending real-host evidence** | `deploy-private-dev` (#36 open; the fake-boundary tests run in CI, the real command has not completed a deployment yet), backup/restore/rollback (#44 open) |
| **Policy without mechanism** | Named private release (tag, version, evidence record), rollback by previous digest, Postman execution |

---

## 2. Overall DevOps assessment

**Reliable.** Of the last 25 `build-and-verify` runs, every `push` to `main`
succeeded; pull-request failures were genuine (a Sonar gate and a backend test on a
runtime-configuration branch, one cancelled superseded run). No retry mechanism
hides anything: `retries: 0` in Playwright, no `continue-on-error`, and the
lifecycle forbids turning an unreliable test into a pass. One serial browser test
(the real Keycloak "concurrent ETag conflict") was reported as intermittent once in
issue #43; no measured rate exists (`DR-12`).

**Reproducible.** Maven Wrapper, committed lockfiles with `npm ci --ignore-scripts`,
digest-pinned base images, SHA-pinned actions, a fixed application clock in the
packaged browser gate, generated OpenAPI artefacts diffed against the committed
copies. The one deliberate non-reproducibility is the `apk upgrade` layer in the
runtime image, accepted for CVE hygiene and identified by digest instead
(recorded as `DEL-04` in the earlier review; not repeated here).

**Sufficiently automated.** Everything from pull request to published image is
automatic on green; deployment is manual by decision (owner-invoked, on the host,
with an explicit digest). The manual steps that remain, release tagging and the
Postman run, are the two gaps this review names.

**Simple to operate.** Three workflows, one classifier script, one gate script,
one local script per gate with 1:1 parity, and a `validate` skill that maps changed
paths to the minimum check. Diagnosing a CI failure is straightforward: artefacts
for Playwright reports, sanitized identity diagnostics, JaCoCo and image evidence
are uploaded; the gate script prints which job was expected and what it returned.

**Efficient for one developer.** A documentation PR runs one job for a few seconds;
a frontend PR runs static checks in under a minute plus a three-minute packaged
browser gate; a backend PR runs the full Testcontainers suite in under two minutes
plus Sonar. The inefficiencies are repetitions (`DR-07`) that cost diagnostic
clarity more than minutes, since GitHub Actions minutes are free for this public
repository.

**Proportional to risk.** No stage exists without a stated regression it detects.
The one place proportion is off is the opposite direction: a missing control
(`DR-01`) rather than an excessive one.

---

## 3. Current strengths

- **Gate semantics are precise.** `verify-ci-results.sh` fails an aggregate gate
  when an applicable job fails, is cancelled or is skipped, and also when an
  inapplicable job runs; unknown paths and CI-logic changes select everything;
  `test-ci-change-detection.sh` unit-tests the classifier whenever CI logic changes.
- **Workflow security is done properly.** Top-level `permissions: {}`, per-job
  minimal grants, `persist-credentials: false` on every checkout, only
  `pull_request` (never `pull_request_target`), Dependabot excluded from the one
  secret-bearing job, publication with `packages: write` only, and every `uses:`
  pinned to a commit SHA with a version comment and updated weekly by Dependabot.
- **Build-once, promote-the-same-bytes is real.** The `main` run builds one OCI
  index, inspects it, runs both platform images through readiness and the HTTP
  boundary, runs the packaged migration actor inside the image, scans with Trivy
  (`--exit-code 1` on HIGH/CRITICAL, plus secret scanning), writes CycloneDX SBOMs,
  uploads the archive with a checksum, and the publish job downloads that exact
  archive, verifies the checksum, copies it with `skopeo --all --preserve-digests`,
  and refuses to finish unless the registry digest equals the scanned one.
- **Deployment is a verification chain, not a push.** Host name check, non-blocking
  lock, digest format check, tag-to-digest equality, OCI label checks, pull by
  digest, image ID compared with the created container, migration before
  replacement, readiness and smoke bound to the new container, evidence JSON even on
  failure, and no rollback claim it cannot keep.
- **Tests sit at real seams.** Pure domain and application tests with fakes;
  PostgreSQL 18 Testcontainers for constraints, queries, ordering, privileges and
  Flyway checksum drift; ArchUnit and Modulith fitness functions; MockMvc contract
  tests validated against the reviewed OpenAPI source; Vitest/RTL with fetch stubs;
  packaged Chromium journeys with axe on every public page; a real Keycloak 26.7
  identity gate; provider tests on deterministic fixtures; opt-in scalability ITs
  with `EXPLAIN` evidence kept out of CI by design.
- **Local parity is 1:1.** Every CI job is one script the developer can run, the CI
  guide is the catalogue, and the `validate` skill and lifecycle discourage running
  everything.

---

## 4. Findings

Priority meaning: `HIGH` = compromises regression detection, reproducibility,
artefact integrity, supply-chain security or the ability to deploy and recover
with confidence; `MEDIUM` = a real gap or misplaced validation; `LOW` = worth
doing opportunistically. Type: `CURRENT_PROBLEM` exists today; `IMPROVEMENT` is a
better fit; `REVISIT_TRIGGER` is not current work.

### HIGH

#### `DR-01` — `main` has no branch protection or ruleset, so the "required" gates are conventions

- **Priority:** HIGH · **Type:** CURRENT_PROBLEM · **Area:** CI / release
- **Evidence:** `GET /repos/rubhern/videogame-platform/branches/main/protection`
  → `404 Branch not protected`; `GET /repos/…/rulesets` → `[]`; repository
  settings allow squash, merge and rebase merges and do not delete branches on
  merge; direct commits on `main` exist (`bf53486 Current OCI state previous to
  destroy`, `f45574d Add claude general review`); `delivery-lifecycle.md`
  ("Required checks must pass and the owner reviews the complete diff");
  `continuous-integration.md` ("Stable gates: Required quality gate, Required
  security gate").
- **Problem observed:** nothing on GitHub requires the two aggregate gates, a pull
  request, a review, or a linear history; force-pushes to `main` are possible. The
  documentation describes an enforced contract that is not enforced.
- **Impact:** publication is still safe (the publish job depends on the gates
  inside the same run), so no unvalidated image can reach GHCR. The risk is to
  traceability and trust: a force-push can make a published `sha` tag point at a
  commit that no longer exists on `main`, a red PR can be merged, and "trusted
  `main`" rests on habit. For a solo project this is also the cheapest possible
  protection against one's own slip.
- **Proposal:** a single ruleset on `main` (free for public repositories): require a
  pull request, require the two status checks `Required quality gate` and
  `Required security gate`, block force-pushes and deletions, and enable
  "delete branch on merge". Optionally restrict merge methods to squash (or
  squash + merge) so `github.event.before` in the push classifier always sees a
  linear parent; the repository already uses all three, which is why some `main`
  commits are merge commits. Keep "require branches up to date" **off**: the `main`
  run's `--full` classification is the stale-branch safety net the CI guide
  already relies on.
- **Simpler alternative considered:** leave it and rely on discipline. Rejected
  because the control costs nothing and the documents already claim it.
- **Trade-offs:** the owner can no longer hot-fix `main` directly; an emergency still
  goes through a pull request, which the lifecycle's `Emergency` row already
  expects.
- **Cost:** small · **Issue:** yes

### MEDIUM

#### `DR-02` — Provenance is documented but not produced, and the SBOM outlives nothing

- **Priority:** MEDIUM · **Type:** IMPROVEMENT · **Area:** supply-chain / artefacts
- **Evidence:** `mvp-platform-and-delivery.md` ("Trusted `main` builds/scans the same
  index, produces SBOM/provenance evidence, and publishes to GHCR");
  `scripts/validate-container-image.sh:426` (`--provenance=false`);
  `build-and-verify.yml` (`application-image-evidence-*` retained 7 days,
  publication record retained 30 days); no `attest-build-provenance`,
  `attest-sbom` or `cosign` step; `deploy-private-dev` verifies labels and digest but
  no attestation.
- **Problem observed:** the only provenance is `build-metadata.json` in a seven-day
  artefact, and the CycloneDX SBOMs expire with it while the image they describe
  stays in GHCR indefinitely. The deployment command trusts "the digest the
  `sha` tag points at" plus OCI labels, both of which anyone with `packages: write`
  could reproduce. The design's claim is broader than what exists.
- **Impact:** low today (one consumer, one publisher, the workflow token is the only
  writer), but the gap is between a documented control and its absence, and the
  fix is free for a public repository.
- **Proposal:** in `publish-container-image`, add `actions/attest-build-provenance`
  and `actions/attest-sbom` (GitHub artifact attestations; `id-token: write` and
  `attestations: write` on that job only) against the published digest with the
  existing CycloneDX SBOM as subject; raise evidence retention to 90 days; and add
  `gh attestation verify oci://<image@digest> --owner rubhern` to the deployment
  preflight so the host refuses an image the workflow did not build. Alternatively
  amend the platform design to say "SBOM evidence; provenance is the tag-to-digest
  and label verification", which is honest but weaker.
- **Simpler alternative considered:** cosign keyless signing. Rejected: it adds a
  tool on the host and a second trust root when GitHub attestations already
  integrate with `gh`.
- **Trade-offs:** the deployment host needs `gh` (or the attestation bundle
  verified in CI and recorded); the publish job gains two steps.
- **Cost:** small · **Issue:** yes (grouped with `DR-03`)

#### `DR-03` — Digest pins outside Dockerfiles have no update path, and Dependabot does not cover Docker at all

- **Priority:** MEDIUM · **Type:** CURRENT_PROBLEM · **Area:** supply-chain
- **Evidence:** `.github/dependabot.yml` (ecosystems: npm `/`, maven `/`, maven
  `/tools/igdb-poc`, github-actions; no `docker`, no `docker-compose`, no npm for
  `/deploy/private-dev/smoke`); digest pins in `Dockerfile` (Node, JDK, JRE),
  `deploy/private-dev/keycloak/Dockerfile`, `deploy/private-dev/smoke/Dockerfile`,
  `deploy/private-dev/compose.yaml` (PostgreSQL, collector), `compose.yaml`,
  `scripts/validate-browser.sh` (Playwright, an untagged `eclipse-temurin@sha256:…`
  JRE, PostgreSQL), `scripts/validate-identity.sh`, `scripts/validate-container-image.sh`
  (Trivy), `build-and-verify.yml` (binfmt, BuildKit, skopeo).
- **Problem observed:** the repository pins ~15 container references by digest,
  which is right, but only the Actions pins are refreshed automatically. Base-image
  CVEs are caught by Trivy *after* the fact and then need a manual digest hunt; the
  smoke runner's Playwright npm dependencies are never bumped; the browser gate
  runs the jar in a JRE whose version nobody can read from the file.
- **Impact:** slow drift toward stale, vulnerable bases and a manual chore nobody
  is reminded of; the `apk upgrade` layer masks it for the runtime image only.
- **Proposal:** add Dependabot `docker` entries for `/`, `/deploy/private-dev/keycloak`
  and `/deploy/private-dev/smoke`, a `docker-compose` entry for `/` and
  `/deploy/private-dev` (supported since 2024; verify on first run), and an npm
  entry for `/deploy/private-dev/smoke`. For the digests in scripts and workflow
  `env` values that Dependabot cannot see, either move them into a small
  `scripts/images.env` that one monthly reminder refreshes, or at least put the
  tag next to every digest so a human can compare. Replace the untagged JRE digest
  in `validate-browser.sh` with the Dockerfile's runtime image reference (`DR-10`).
- **Simpler alternative considered:** Renovate. Rejected: another bot with its own
  config for a gap Dependabot mostly closes.
- **Trade-offs:** more Dependabot PRs (bounded by `open-pull-requests-limit`); each
  base-image bump re-runs the container gate, which is the point.
- **Cost:** small · **Issue:** yes (grouped with `DR-02`)

#### `DR-04` — Postman collections are a contract artefact that CI never executes

- **Priority:** MEDIUM · **Type:** CURRENT_PROBLEM · **Area:** testing
- **Evidence:** `AGENTS.md` ("Whenever a backend API is added or modified, update
  its tracked Postman collection, requests, and assertions in the same change");
  `backend/postman/README.md` ("with automated tests"); `scripts/validate-docs.sh`
  (checks the files exist only); no Newman step in any script or workflow;
  `detect-ci-changes.sh:173` (`backend/postman/*` → `documentation backend`).
- **Problem observed:** six collections with assertions are maintained by policy
  on every API change and validated by nothing but their JSON parsing. They can
  drift silently (the earlier code review's `CR-01` shows the same schema field
  carrying two value spaces, which a running collection would have flagged or
  encoded). The MockMvc contract tests already give the *machine* evidence; the
  collections are the *human-runnable* evidence and currently the two can diverge.
- **Impact:** either the collections quietly stop being true, or the "same change"
  rule is paid for with no return.
- **Proposal:** run the public collections (releases, search, game details,
  session, actuator) with Newman inside `scripts/validate-browser.sh`, where the
  packaged application and the dev seed are already up; the cost is one npm dev
  dependency and roughly thirty seconds. The personal-ratings collection needs a
  browser-created session and stays manual, documented as such. If the owner
  prefers not to execute them, downgrade the `AGENTS.md` rule to "update when the
  operation changes, run manually before a named release" so the policy matches
  reality.
- **Simpler alternative considered:** delete the collections and keep only the
  MockMvc contract tests. Defensible, but the collections are the only executable
  documentation an operator can open, and the actuator collection covers the
  synchronization command the browser gates never touch.
- **Trade-offs:** one more dependency in the frontend workspace or root; failures in
  the browser gate now include API assertion failures, which is desirable.
- **Cost:** small · **Issue:** yes

#### `DR-05` — Release engineering is defined but has never been exercised: no tag, no release, `SNAPSHOT` in every image

- **Priority:** MEDIUM · **Type:** CURRENT_PROBLEM (for MVP close-out) · **Area:** release
- **Evidence:** `git tag` empty; `gh release list` empty; `pom.xml`
  `0.15.0-SNAPSHOT`, `frontend/package.json` `0.8.0`, OpenAPI `1.2.3`;
  `Dockerfile` (`org.opencontainers.image.version` from the Maven version, so every
  published image is labelled `…-SNAPSHOT`); `delivery-lifecycle.md` ("A named
  release removes the suffix and uses the matching `vX.Y.Z` tag … A named private
  release records version/tag, accepted journey evidence, known limits, and image
  digest"); no release workflow, no changelog, no release template.
- **Problem observed:** the policy is complete and the mechanism is absent. Closing
  the MVP means producing the first named private release, and today nobody could
  say which commit, version and digest that is.
- **Impact:** the MVP cannot be "closed" in the lifecycle's own terms; the deploy
  evidence records `0.15.0-SNAPSHOT` for what may be the accepted build.
- **Proposal:** a documented four-step procedure, no new tool: (1) a release PR
  that sets the Maven version to `X.Y.Z` (and the frontend/OpenAPI versions if they
  changed), reviewed and merged normally, so `main` builds and publishes the image
  labelled `X.Y.Z`; (2) the owner creates an annotated tag `vX.Y.Z` on that merge
  commit and a GitHub Release whose notes hold the image digest, the deployment
  evidence identifier and the #45 journey evidence; (3) optionally a tiny
  `on: push: tags: v*` workflow that resolves the tag commit's published digest and
  adds an immutable `vX.Y.Z` tag to the same GHCR digest with `skopeo copy`; (4)
  the next PR bumps to `X.Y+1.0-SNAPSHOT`. `validate-docs.sh` already enforces the
  semver shape and parent/reactor equality.
- **Simpler alternative considered:** declare "the digest deployed on `dev` is the
  release" without a tag. Rejected: the lifecycle requires the tag and it costs
  nothing.
- **Trade-offs:** two extra PRs per release; a small workflow if step 3 is wanted.
- **Cost:** small · **Issue:** yes

#### `DR-06` — HTTP-delivery and contract changes do not select the packaged browser gate, and no packaged test reloads each SPA route

- **Priority:** MEDIUM · **Type:** CURRENT_PROBLEM (detection gap) · **Area:** CI / testing
- **Evidence:** `scripts/detect-ci-changes.sh` (`backend/src/main/*` →
  `backend sonar codeql_java`; `docs/architecture/api/openapi.yaml` →
  `documentation openapi frontend backend`; neither selects `browser`);
  `FrontendRouteController` forwards `/`, `/search`, `/games/*` only while the SPA
  declares `/mis-puntuaciones` (code review `CR-02`); the packaged specs navigate
  in-app and never `page.goto()` a deep link other than `/`, `/search…` and a game
  path; the identity gate reaches `Mis puntuaciones` through the header link.
- **Problem observed:** the packaged browser gate is the only automated evidence
  that the backend actually serves the frontend, yet a change to `api.delivery`
  (controllers, exception mapping, the SPA forwarder) or to the contract itself
  runs it only on `main`. A missing route forward is invisible to every existing
  test because none reloads a deep link.
- **Impact:** regressions at the backend/frontend seam are found after merge; one
  such regression has already shipped.
- **Proposal:** (1) add `browser` to the classification of
  `backend/src/main/java/com/videogameplatform/api/*` and of `openapi.yaml`, and
  add both cases to `test-ci-change-detection.sh`; the cost is a three-minute job
  on those PRs. (2) Add one packaged spec that, for every route in
  `frontend/src/app/router.tsx` (`/`, `/search`, `/mis-puntuaciones`,
  `/games/<seed-game>/<slug>`, an unknown path), issues a direct `page.goto()` and
  asserts the SPA shell renders (or the anonymous state for the personal page)
  rather than a bare `404`. This is the cheapest test that would have caught
  `CR-02`.
- **Simpler alternative considered:** rely on the `main` full run. Rejected: it is
  the current state and it let the defect through.
- **Trade-offs:** slightly more PRs run the browser gate; one more spec.
- **Cost:** small · **Issue:** yes (grouped with `DR-07`)

#### `DR-07` — Packaging runs the whole backend test suite, so three other gates repeat it and the `migrations` job duplicates `backend`

- **Priority:** MEDIUM · **Type:** IMPROVEMENT · **Area:** CI / developer-experience
- **Evidence:** `scripts/package-application.sh` (`./mvnw -Pwith-frontend clean package`
  without `-DskipTests`; `backend/pom.xml` has no Surefire configuration, so every
  `*Test`/`*IntegrationTest` class runs in the `test` phase); `validate-browser.sh`
  and `validate-identity.sh` call it; the identity job log shows the full
  `Tests run:` sequence before Keycloak starts; `sonarcloud` runs `clean verify`
  again; `scripts/validate-migrations.sh` runs two test classes that `backend`'s
  `clean verify` already runs, and every path that enables `migrations` also
  enables `backend`. Measured on the latest `main` run: backend 1.7 min,
  browser 3.1 min, identity 3.2 min, Sonar 2.9 min, migrations 0.8 min.
- **Problem observed:** on a `main` push the Testcontainers suite executes four
  times; on a frontend-only pull request it executes once inside the browser gate,
  so a flaky backend integration test can fail a CSS change; the `migrations` job
  adds a runner and a name but no evidence.
- **Impact:** wasted minutes are cheap here (public repository), but the diagnostic
  cost is real: the browser and identity jobs report backend test failures under
  the wrong heading, and their logs are dominated by Maven output.
- **Proposal:** `-DskipTests` in `package-application.sh` (the `backend` job is the
  test evidence; when `browser` or `identity` run without `backend`, the change by
  construction did not touch backend sources); keep `clean` so the frontend
  resources are re-embedded. Remove the `migrations` job and its classification
  output, or keep the job only if the owner values its separate name in the checks
  list. Let Sonar reuse the `backend` job's JaCoCo report by downloading the
  `backend-jacoco` artefact and running `sonar:sonar` after a `-DskipTests`
  compile, or leave Sonar as is and accept one repetition.
- **Simpler alternative considered:** do nothing; minutes are free. Rejected for the
  diagnostic reason above; the change is one flag.
- **Trade-offs:** none for evidence; `validate-browser.sh` run locally becomes
  three minutes faster.
- **Cost:** small · **Issue:** yes (grouped with `DR-06`)

### LOW

#### `DR-08` — Rollback by previous digest is possible but undocumented and unassisted

- **Priority:** LOW · **Type:** IMPROVEMENT · **Area:** deployment
- **Evidence:** `deploy/private-dev/README.md` ("Automatic rollback … remain #44");
  `deploy-private-dev` records the digest in each evidence JSON; migrations are
  forward-only with an expand/contract policy.
- **Problem observed:** the same command with the previous digest is a valid
  rollback whenever the schema is still compatible, and the previous digest is in
  the last successful evidence record; neither fact is written down and the script
  does not surface it.
- **Proposal:** a "Roll back" paragraph in the private-dev README naming the
  precondition (no migration since, or a compatible one), and a preflight line in
  the script that prints the last successful evidence record's digest and
  migration version so the operator sees what they are replacing.
- **Cost:** small · **Issue:** no (fold into #44 or the host runbook issue from the infrastructure review)

#### `DR-09` — Nothing rescans the published digest between pushes

- **Priority:** LOW · **Type:** IMPROVEMENT · **Area:** supply-chain
- **Evidence:** Trivy and CodeQL run only on `pull_request` and `push`; no
  `schedule` trigger anywhere; the deployed image is the last `main` digest.
- **Problem observed:** a base-image or library CVE published during a quiet
  fortnight is discovered only at the next push.
- **Proposal:** a weekly `schedule` job that pulls the last published digest (from
  the latest publication artefact or GHCR) and runs the existing Trivy invocation;
  CodeQL's default setup also offers a weekly schedule. Keep it informational
  (issue or summary), not a gate.
- **Cost:** small · **Issue:** no (fold into the `DR-02`/`DR-03` issue if accepted)

#### `DR-10` — The browser gate runs the jar in an untagged JRE digest that is not the image's runtime

- **Priority:** LOW · **Type:** IMPROVEMENT · **Area:** testing / build
- **Evidence:** `scripts/validate-browser.sh:8` (`eclipse-temurin@sha256:f9e6…`, no
  tag, no comment); `Dockerfile` (`eclipse-temurin:25.0.4_7-jre-alpine-3.23@sha256:f8b3…`).
- **Problem observed:** the packaged journey is proven on a JRE nobody can name
  from the file, and it differs from the runtime the image ships. The container
  gate separately proves the image starts and answers health, so the journey and
  the runtime are validated on different JVMs.
- **Proposal:** reference the same tag@digest as the Dockerfile's `JRE_IMAGE`
  (or build-arg it from one place), which also lets `DR-03`'s Dependabot coverage
  reach it.
- **Cost:** small · **Issue:** no (fold into `DR-03`)

#### `DR-11` — Contract conformance relies on a 321-line hand-rolled JSON Schema validator

- **Priority:** LOW · **Type:** IMPROVEMENT · **Area:** testing
- **Evidence:** `backend/src/test/java/com/videogameplatform/api/delivery/OpenApiResponseContract.java`;
  earlier review `TEST-03`.
- **Problem observed:** the validator supports the subset of OpenAPI 3.1 the
  contract uses today; a new keyword (`oneOf` discriminators, `patternProperties`,
  `const`) is silently unvalidated until someone notices. It has served well and
  has no known false positives.
- **Proposal:** none now. Revisit with a maintained validator only when a contract
  change uses a keyword the class does not implement; a unit test asserting the
  supported keyword set would make that moment visible.
- **Cost:** small · **Issue:** no

#### `DR-12` — One serial identity-gate test was reported intermittent; there is no measured rate

- **Priority:** LOW · **Type:** IMPROVEMENT · **Area:** testing
- **Evidence:** issue #43 comment ("stopped on an existing ratings ETag scenario:
  the page received a conflict before the test-created conflict"); the last 25
  runs show no confirmed identity failure on `main`; `rating-boundary.spec.ts`
  runs `serial` with `--trace=off` (deliberately, so credentials never enter a
  trace).
- **Problem observed:** a real-Keycloak, real-PostgreSQL browser test that
  manufactures a concurrent conflict is the most timing-sensitive test in the
  repository, and its diagnostics are limited by design.
- **Proposal:** keep `retries: 0`; on failure upload the sanitized `test-results/`
  as today plus the application container log (already collected in
  `validate-identity.sh`); if a second occurrence is seen, make the conflict
  deterministic by creating the competing write through the API with the current
  ETag rather than through a second browser context.
- **Cost:** small · **Issue:** no (monitor)

#### `DR-13` — Merge history is mixed and stale branches accumulate

- **Priority:** LOW · **Type:** IMPROVEMENT · **Area:** release
- **Evidence:** repository settings (squash, merge and rebase all enabled;
  delete-on-merge off); `main` contains both squash commits and merge commits.
- **Problem observed:** cosmetic for one developer, but the push classifier's
  `github.event.before` and the "one commit = one image" story are simplest with a
  linear history, and stale `codex/*`/`claude/*` branches stay behind.
- **Proposal:** part of the `DR-01` ruleset: squash-only (or squash + merge) and
  delete branches on merge.
- **Cost:** small · **Issue:** no (fold into `DR-01`)

---

## 5. CI efficiency and reliability

Measured on the latest `main` run at the reviewed revision (job wall-clock):

| Job | Minutes | Notes |
|---|---|---|
| Detect affected CI areas | 0.1 | full history checkout, whitespace check, classifier |
| Documentation and API contracts | 0.2 | docs validator; OpenAPI lint and generated-doc diff when applicable |
| Frontend static, type, component and build checks | 0.6 | `npm ci`, type generation diff, `frontend:verify` |
| Java backend, architecture and PostgreSQL integration | 1.7 | `clean verify` with Testcontainers, Spotless, JaCoCo |
| Fresh PostgreSQL 18 migration | 0.8 | two test classes already in `backend` (`DR-07`) |
| IGDB PoC local fixtures | 0.3 | closed spike, negligible |
| SonarQube Cloud quality gate | 2.9 | second `clean verify` (`DR-07`) |
| Packaged application Chromium smoke and accessibility | 3.1 | `npm ci` + Maven package with tests (`DR-07`) + Playwright container |
| Real Keycloak 26.7 OIDC BFF compatibility | 3.2 | same packaging + Keycloak + two specs |
| Multi-architecture application image | 6.4 | private-dev static/telemetry checks, QEMU arm64, two builds, two runtime proofs, two Trivy scans, two SBOMs |
| Required quality gate / Publish | 0.0 / 0.7 | aggregate check; skopeo copy and digest verification |

Wall-clock ≈ 8–9 minutes, bounded by the container job; total ≈ 20 runner-minutes,
free on a public repository. Pull requests run a subset: a documentation change
takes seconds, a frontend change about four minutes, a backend change about five
including Sonar.

**Selection.** The classifier is explicit, tested, conservative on unknown paths,
and mirrors the local `validate` skill. Its two gaps are in `DR-06`. `deploy/private-dev/*`
selects `backend`, `migrations`, `identity` and `container`, which is broad but
correct because the Compose file, realm and bootstrap script are shared contracts.

**Caching.** npm and Maven caches keyed on lockfiles; BuildKit layers cached through
`type=gha`; Playwright runs from a pinned container so no browser install; Trivy's
database is downloaded per run (about half a minute; not worth caching yet).

**Skipped/cancelled behaviour.** Correct: `cancelled` and `skipped` fail an
applicable job; an unexpected run fails too. PR runs cancel superseded runs; `main`
runs never cancel, so publication cannot be lost to a fast second push.

**Isolation between PR and `main`.** PRs never publish or deploy, receive only
`SONAR_TOKEN` (same-repo, non-Dependabot), and build their own image only for
scanning. `main` rebuilds rather than reusing PR artefacts, which is the right
choice: PR artefacts are untrusted by construction.

**Stale branches.** No "up to date" requirement; the `main` `--full` run is the
net. Adequate for one developer; `DR-01` keeps it that way deliberately.

**Reliability.** No `main` failure in the sample; no retries; one reported
intermittent browser test (`DR-12`). A logback `ConsoleAppender … The name 'error'
has already been written` stack trace appears in test logs during the structured
profile; it is noise that makes CI logs harder to read (`OUT_OF_SCOPE: OBSERVABILITY`
for the fix, noted here because it affects diagnosis).

---

## 6. Testing strategy assessment

| Seam | Evidence today | Placement | Assessment |
|---|---|---|---|
| Domain and application policies | JUnit/AssertJ with hand-written fakes, fixed clocks | unit | Right level; fast; no mocks of value objects |
| Persistence (constraints, queries, ordering, privileges, Flyway drift) | Testcontainers PostgreSQL 18, one shared container per JVM, isolated databases per class | integration | Right level; under two minutes for the whole suite |
| Architecture | ArchUnit hexagonal rules, Modulith `verify()`, provider boundary | unit | Right level; missing only the SQL-string cross-module check named in the architecture review |
| HTTP contract | MockMvc + `OpenApiResponseContract`, Redocly lint, generated-type and generated-doc diffs | integration + static | Right level; validator is hand-rolled (`DR-11`); Postman never executed (`DR-04`) |
| Frontend components and pages | Vitest/RTL with `fetch` stubs, role-based queries | unit | Right level; stub duplication noted in the code review |
| Packaged browser journeys and accessibility | Playwright in a pinned container against the packaged jar and dev seed, axe on every public page, fixed clock, `retries: 0` | e2e (few, critical) | Right level and size; missing the route-reload spec (`DR-06`) and selected too narrowly on PRs (`DR-06`) |
| Identity/BFF | Real Keycloak 26.7 via Compose, two specs covering login/logout, rating boundary, `Mis puntuaciones`, self-registration; Spring Security integration tests | e2e + integration | Right level; the only expensive e2e and it earns it; one intermittent report (`DR-12`) |
| Migrations | Fresh-database migration test, naming validation, checksum drift, runtime-privilege test, packaged migration actor inside the image | integration + container | Right level; the standalone job is redundant (`DR-07`) |
| Container | Both architectures built, inspected, started, readiness and HTTP boundary asserted, packaged migrations run, Trivy, SBOM | container | Right level; arm64 runtime proof is under QEMU (`RT-1`) |
| Provider | Deterministic fixture server for the adapter and client; IGDB PoC tool tests; no live credentials in CI | integration | Right level |
| Deployment smoke | Playwright container against the private HTTPS boundary: readiness, version, metrics, releases page, real Keycloak session, trace receipt; fake-boundary tests in CI | smoke | Right design; not yet exercised on the host (#36) |
| Scalability | Opt-in `*IT` with `EXPLAIN (ANALYZE, BUFFERS)` evidence, excluded from CI by Surefire defaults | opt-in | Right decision |

**Gaps that matter:** the route-reload spec (`DR-06`) and executed Postman evidence
(`DR-04`). **Duplication that matters:** the test suite inside packaging (`DR-07`)
and the standalone `migrations` job. **Nothing here is too slow**, and no test was
found whose cost exceeds its evidence. Coverage is collected (JaCoCo, imported into
Sonar) and not used as a target, consistent with the baseline.

---

## 7. Build and artefact integrity

The chain from source to registry, as implemented:

```text
openapi.yaml ──(openapi-typescript, diffed)──▶ frontend types
             ──(openapi-generator, target/)──▶ backend interfaces
frontend/ ──npm ci --ignore-scripts, vite build──▶ dist/  ─┐
backend/  ──mvnw -Pwith-frontend,production-image -DskipTests──▶ application.jar (dev-seed excluded, build-info with revision)
Dockerfile (three digest-pinned stages, non-root 10001, OCI labels: source/revision/version)
  ──buildx linux/amd64,linux/arm64, --provenance=false, type=oci──▶ application-image.oci.tar + SHA256SUMS + image-digest.txt
  ──per-platform --load──▶ run readiness + HTTP boundary + packaged migration; Trivy (HIGH/CRITICAL, secrets); CycloneDX SBOM
main only: upload tar (1 day) ──▶ publish job: sha256sum --check ──▶ skopeo copy --all --preserve-digests ──▶ ghcr.io/…:<sha>
           ──▶ imagetools inspect: digest == scanned digest, both platforms present ──▶ publication record (30 days)
```

Verified properties: the published bytes are the scanned bytes (checksum and
digest equality); the tag is the commit SHA and never `latest`; the version label
comes from the Maven reactor; the source revision is in both `/actuator/info` and
the OCI label; the deployment script re-verifies all three. The frontend build,
the jar and the image are reproducible up to the `apk upgrade` layer and the
floating patch versions of `setup-java`/`setup-node` (the Dockerfile pins them).
Gaps: attestation and SBOM retention (`DR-02`), digest maintenance (`DR-03`), and
`SNAPSHOT` versions in every image (`DR-05`).

---

## 8. Deployment and release assessment

**Implemented mechanism** (`deploy-private-dev`): owner-invoked on the host, host
name check, non-blocking lock, mandatory immutable digest and full source SHA,
tag-to-digest equality, pull by digest, OCI label verification, smoke-runner build,
one-shot Flyway migration with the migrator role, `up --force-recreate --no-deps --wait`
of the application only, container image ID verification, deployment smoke against
the private HTTPS boundary, correlation/trace evidence in application logs and
collector receipt, atomic JSON evidence with phase and outcome. `test-private-dev-deployment.sh`
proves the ordering, lock and failure semantics against a fake boundary in CI.

**Status:** approved and pending. #36 is open; the four most recent `main` commits
are fixes discovered while trying the command on the host, which is exactly what
"repository validation is not evidence that either has run successfully" warned.
Until the first evidence record exists, "we can deploy with confidence" is a design
statement.

**Serialization:** one host lock; concurrent deployments refused. **Readiness:**
bound to the new container with a 180-second wait. **Smoke:** bounded, no IGDB, no
rating written, clear about not being product acceptance. **Failure handling:**
evidence records the phase; the previous application keeps running if migration
fails; if activation or smoke fails the candidate stays up and the record says
`failure` (no automatic rollback, by decision). **Rollback:** by previous digest
when schema-compatible, undocumented (`DR-08`); backup/restore is #44
(`OUT_OF_SCOPE: INFRASTRUCTURE` for the mechanism). **Separation from
acceptance:** explicit everywhere; #45 owns the journey.

**Release:** policy only (`DR-05`).

---

## 9. Supply-chain assessment

| Control | State | Note |
|---|---|---|
| Action pinning | SHA + version comment on every `uses:`; Dependabot weekly | good |
| Workflow permissions | `permissions: {}` at top; minimal per job; `persist-credentials: false` | good |
| Event model | `pull_request` only; no `pull_request_target`; PRs never publish | good |
| Secrets in CI | `SONAR_TOKEN` (same-repo PRs, `main`), `github.token`; no deploy or provider secrets | good |
| Secret scanning | Gitleaks over full history on every PR and push | good |
| Dependency review | `dependency-review-action`, fail on high, on PR and push | good |
| npm audit | high threshold when npm dependencies change | good |
| Maven dependency graph | submitted for both reactors on `main` so Dependabot alerts cover Maven | good |
| CodeQL | Java (manual build) and JavaScript/TypeScript, `security-extended`, on change | good; no schedule (`DR-09`) |
| Image scanning | Trivy 0.74 per platform, HIGH/CRITICAL fail, secret scanner | good; no rescan between pushes (`DR-09`) |
| SBOM | CycloneDX per platform, 7-day artefact | gap: retention, not attached (`DR-02`) |
| Provenance | BuildKit provenance disabled; no attestation; labels + tag/digest check only | gap versus documentation (`DR-02`) |
| Base-image pinning | digests everywhere; no Dependabot `docker` | gap (`DR-03`) |
| Publication | `packages: write` only; skopeo preserve-digests; digest verified | good |
| Deploy-time verification | digest, tag, labels, image ID | good; no attestation check (`DR-02`) |
| Branch integrity | none (`DR-01`) | gap |

---

## 10. Revisit triggers

| ID | Revisit … | When … | First move inside GitHub Actions and scripts |
|---|---|---|---|
| `RT-1` | QEMU-emulated arm64 build and runtime proof | The container job becomes the pull-request bottleneck, or native arm64 evidence is required for a release | Split the two platforms across `ubuntu-24.04` and `ubuntu-24.04-arm` (free for public repositories) and merge the index; no new tool |
| `RT-2` | Duplicated `detect-changes` job in two workflows | A third workflow needs the classification | Reusable workflow or a composite action |
| `RT-3` | Sonar quality gate blocking `main` publication (`sonar.qualitygate.wait=true`) | A SonarCloud outage blocks a needed image, or the gate's "new code" conditions fight the coverage policy | Make the `main` Sonar step non-blocking through `verify-ci-results.sh` (expected `true` but tolerate `failure` with a summary) while keeping PR analysis blocking |
| `RT-4` | Trivy database caching and Playwright/Maven output verbosity | Job times or log sizes become a diagnosis obstacle | `actions/cache` keyed by date for the Trivy DB; `--quiet`/`-ntp` where missing |
| `RT-5` | A tag-driven workflow for GHCR version tags (`DR-05` step 3) | The first named release shows manual re-tagging is error-prone | Ten-line workflow on `push: tags: v*` using the existing skopeo pattern |

---

## 11. Things reviewed but intentionally not recommended

- **Any other CI/CD platform or GitOps controller** (Jenkins, GitLab CI, ArgoCD,
  Tekton, Flux). Nothing here needs more than GitHub Actions plus scripts, and the
  deployment is deliberately owner-invoked.
- **Reusing the pull-request image for `main` publication ("build once").** PR
  artefacts are untrusted; rebuilding on `main` from the merged commit is the
  right trust boundary and costs six minutes.
- **A separate deployment workflow in GitHub Actions.** The platform design forbids
  deploying from `main` automatically and the host has no inbound path for a
  runner; the owner-run script is the mechanism.
- **cosign or Sigstore beyond GitHub artifact attestations.** Attestations cover the
  need with tooling already present (`gh`).
- **Coverage thresholds, mutation testing, Pact-style consumer contracts.** The
  baseline says coverage is evidence; the OpenAPI conformance tests already bind
  both sides of the contract in one repository.
- **Merging the security and build workflows.** Separate concurrency groups and
  gates are clearer; the duplicated classifier job costs five seconds (`RT-2`).
- **Removing the IGDB PoC job.** Eighteen seconds for a tool that documents the
  provider evidence; not worth a decision.
- **Nightly full runs.** `main` already runs everything on every push; a schedule
  is worth it only for rescans (`DR-09`).
- **Parallelizing or sharding Playwright.** Six packaged specs take about a
  minute inside a three-minute job dominated by packaging (`DR-07` fixes the
  right thing).
- **Testcontainers reuse or a service container for PostgreSQL.** The suite runs
  in under two minutes with one shared container per JVM.
- **Replacing `OpenApiResponseContract` now** (`DR-11`).
- **Requiring branches to be up to date** (`DR-01` keeps this off on purpose).
- **`OUT_OF_SCOPE` notes:** the logback appender error in test logs (Observability);
  rollback data mechanics and backup (Infrastructure, #44); the deployment host's
  `gh`/tooling (Infrastructure).

---

## 12. Suggested GitHub issues

Only work that justifies tracking; related findings are grouped.

| # | Proposed title | Findings | Priority | Cost |
|---|---|---|---|---|
| 1 | Protect `main` with a ruleset that requires the two aggregate gates | `DR-01`, `DR-13` | HIGH | small |
| 2 | Define and run the first named private release | `DR-05` | MEDIUM | small |
| 3 | Attest provenance and SBOM for published images and refresh digest pins automatically | `DR-02`, `DR-03`, `DR-09`, `DR-10` | MEDIUM | small |
| 4 | Execute the public Postman collections in the packaged browser gate | `DR-04` | MEDIUM | small |
| 5 | Select the browser gate for delivery and contract changes, add a route-reload spec, and stop repeating the backend suite in packaging | `DR-06`, `DR-07` | MEDIUM | small |

`DR-08`, `DR-11` and `DR-12` are recorded for the runbook, a future contract change
and monitoring respectively. No `RT-*` trigger becomes an issue.

Suggested order: 1 today (it is a settings change); 2 as the MVP close-out act
itself; 5 before the next backend delivery change; 3 and 4 whenever convenient.

---

## 13. Final assessment

This is a delivery pipeline that already does the hard things right: it validates
selectively without losing evidence, it publishes exactly what it scanned, it
identifies every artefact by commit and digest, and it deploys by explicit digest
with verification at every step. The MVP can be closed on this pipeline. The
recommendations add enforcement to what is already documented (`DR-01`), exercise
a policy for the first time (`DR-05`), move two validations to where they can act
(`DR-04`, `DR-06`), remove one repetition (`DR-07`), and close the distance between
the supply-chain documentation and its implementation (`DR-02`, `DR-03`). None
requires a new platform, a new tool on the host, or a change to any approved
decision.

What was verified: every workflow, script and manifest cited, at `d10bc44`; branch
protection, rulesets, repository settings and variables through the API; job
durations and conclusions of the last 25 `build-and-verify` runs; the absence of
tags and releases. What was assumed: that GitHub Actions minutes remain free for
this public repository; that the owner has not configured protection elsewhere
(organization rulesets do not exist for a personal account); and that #36 will
produce the first real deployment evidence before the MVP is declared closed.
