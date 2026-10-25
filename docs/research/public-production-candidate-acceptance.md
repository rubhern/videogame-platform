# Portable production candidate acceptance — #275

- **Status:** Point-in-time evidence; owner-accepted with conditions on 2026-10-25
- **Scope:** Increment 8 and its security disposition merged; final candidate campaign passed on trusted-main `9ffc05a`; acceptance record signed

This audit supports owner review of [#275](https://github.com/rubhern/videogame-platform/issues/275)
and handoff to [#276](https://github.com/rubhern/videogame-platform/issues/276).
It owns this frozen acceptance assessment, not operating policy. The
[release gates](../development/delivery-lifecycle.md#public-production-gates),
[platform envelope](../architecture/deployment/mvp-platform-and-delivery.md#resource-and-work-envelope-proposal-not-capacity-evidence)
and [prepared operator procedures](../../deploy/public-production/README.md#host-bootstrap-and-operating-handoff-to-276)
remain canonical.

## Recommendation and evidence boundary

**NO-GO for purchase authorization at this handoff.** The implementation and its
[security disposition](#security-disposition--2026-10-25) are merged, and the
[final candidate campaign](#final-candidate-campaign--9ffc05a-2026-10-25) passed on the
exact trusted-main publications. The owner signed the technical acceptance in the
[acceptance record](#owner-acceptance-record) on 2026-10-25, with conditions on three
residual-risk groups. Purchase authorization still needs the contracting-gate
prerequisites. Reconsider purchase only when those conditions
and the contracting-gate commercial/storage prerequisites are evidenced. Public
exposure is independently **NO-GO** until #276 qualification and owner authorization.
Accepted ADR-0025/ADR-0030 and closed #274 do not waive either gate. Real-host
capacity, TLS, mail, networking and storage proofs belong to #276; they are not
requirements to demonstrate before buying the host.

The final candidate campaign selects `9ffc05a2ff30e261564ddbfd412826cdec504137`,
application `0.51.0-SNAPSHOT`; its section below is the current evidence. The
`6c4bbb3`, `c7bc5cd` and older campaigns remain historical for their recorded images and
operational source hashes and do not qualify the `9ffc05a` images.

The database actually migrated through **`20261023.130000`**, including the historical
backfill checkpoint and deferred scheduled-policy migrations introduced on main.
[Migration/scheduler evidence](public-production-candidate-evidence/p1-migration-scheduler.json)
records the running application's `CATALOGUE_SYNC_SCHEDULING_ENABLED=false`.
Neither scheduled synchronization nor real-provider acquisition was exercised.
Synthetic catalogue data and a direct approved-model translation do not prove
scheduler throughput or capacity. No schedule is installed by this work.

The Docker context remained local Docker Desktop (`unix:///var/run/docker.sock`).
The former environment blocker is historical; no host configuration was changed.
New Keycloak email content is covered by an **explicitly executed**, passing
`bash scripts/review-keycloak-email.sh after-public` test, with four multipart flows,
native actions, HTTPS resource URLs and accessibility checks. A separate filesystem
hash comparison proved the c7bc5cd trusted Keycloak image contained those exact email
assets; older image receipts are not evidence for changed content. The template
digests are unchanged at `9ffc05a`, whose browser journeys exercised the email flows
again on its own Keycloak image.

## Final candidate campaign — 9ffc05a, 2026-10-25

The owner ran the four-part campaign on trusted-main
**`9ffc05a2ff30e261564ddbfd412826cdec504137`**, the PR #372 merge that applies the
[security disposition](#security-disposition--2026-10-25). Its
[build/publication](https://github.com/rubhern/videogame-platform/actions/runs/40192338489)
and [Security](https://github.com/rubhern/videogame-platform/actions/runs/40192338507)
runs passed. Every receipt names that revision and those runs, the runtime fingerprint
`f41aa630…bb3b` and exactly the four published digests; none is production-ready. The
receipts were copied unchanged. The [campaign record](public-production-candidate-evidence/final-9ffc05a-campaign.json)
holds their source hashes, the publications and their scan policies.

| Exercise | Result | Receipts |
|---|---|---|
| Combined browser, model, cleanup and observability | **Passed:** all eight checks; 15 native identity actions with zero 429 refusals; approved model; six dashboards, Keycloak scrape and Collector loss; no restart/OOM; teardown | [combined](public-production-candidate-evidence/final-9ffc05a-combined.json), [cookies](public-production-candidate-evidence/final-9ffc05a-combined-cookies.json) |
| Manual deployment procedure (`--rehearsal`, stages A–E) | **Passed:** migration to `20261023.130000`, browser stage C, model, and eight transitions after the initial boot. The injected migration, readiness and smoke failures were detected, synthetic state was preserved, and teardown passed | [deployment](public-production-candidate-evidence/final-9ffc05a-deployment.json), [attempts](public-production-candidate-evidence/final-9ffc05a-deployment-attempts.json), [cookies](public-production-candidate-evidence/final-9ffc05a-deployment-cookies.json) |
| Paired backup and isolated host-loss recovery | **Passed:** every recorded recovery, refusal, replay, retry and cleanup assertion is true | [rehearsal](public-production-candidate-evidence/final-9ffc05a-recovery-rehearsal.json), [backup](public-production-candidate-evidence/final-9ffc05a-backup.json), [restore](public-production-candidate-evidence/final-9ffc05a-restore.json), [replay](public-production-candidate-evidence/final-9ffc05a-deletion-replay.json), [verified recovery](public-production-candidate-evidence/final-9ffc05a-verified-recovery.json) |
| Edge contract | **Passed** as reported by the owner, and reproduced on the same tree; this exercise writes no receipt | campaign record |

**Trusted-main scans at `9ffc05a`:** Caddy has 0 inherited HIGH/CRITICAL findings,
Keycloak 10 and the localizer 63; repository layers add none. They equal the
disposition's local builds. The measurements describe this workstation and synthetic
data, not VPS capacity, RPO or RTO.

## Final integrated campaign — 6c4bbb3, 2026-10-24 (historical)

One campaign ran sequentially from a clean checkout of trusted-main
**`6c4bbb377d8aad85061cd99626864ad594ebb57d`**. That revision is the PR #371 merge plus
a `.gitleaksignore`-only fix; its
[build/publication](https://github.com/rubhern/videogame-platform/actions/runs/40089217088)
and [Security](https://github.com/rubhern/videogame-platform/actions/runs/40089216742)
runs passed. The merge revision's failed Security run (verified digest false positives)
is superseded and was not used. Every exercise used the four publications of that
run by digest, the reviewed runtime fingerprint `1ceeafde…2045`, and the approved
checksum-verified model. No local image, earlier publication or modified configuration
was substituted. The [campaign record](public-production-candidate-evidence/final-6c4bbb3-campaign.json)
binds the source, runs, digests, fingerprint, commands, ordering and receipt hashes.

| Exercise | Result | Receipts |
|---|---|---|
| Combined browser, model, cleanup and observability | **Passed:** browser signup/welcome/ownership/CSRF/recovery with 15 native identity actions and zero 429 refusals; approved-model translation; dry run, in-flight deny/drain, lost-acknowledgement revocation, new-verification race refusal, archive/suppression proof and reopening; application OTLP, Keycloak scrape and six dashboards; Collector loss kept the product available; no restart/OOM; teardown | [combined](public-production-candidate-evidence/final-6c4bbb3-combined.json), [cookies](public-production-candidate-evidence/final-6c4bbb3-combined-cookies.json) |
| Manual deployment procedure (`--rehearsal`, stages A–E) | **Passed:** trusted configuration and digests, real migration to `20261023.130000`, TLS/issuer, browser stage C, model, and eight transitions after the initial boot. The injected migration, readiness and smoke failures were detected, activation was prevented, synthetic state was preserved, and teardown passed | [deployment](public-production-candidate-evidence/final-6c4bbb3-deployment.json), [attempts](public-production-candidate-evidence/final-6c4bbb3-deployment-attempts.json), [cookies](public-production-candidate-evidence/final-6c4bbb3-deployment-cookies.json) |
| Paired backup and isolated host-loss recovery | **Passed:** encrypted pair, in-flight write rollback, source destruction, reconstruction with new credentials, Grafana datasource before backup and after reopening, unknown LOGIN role refused, missing-archive replay refused, deletion replay with only Keycloak, old offline token refused, fenced requested-account retry with original ciphertext and suppression proof, readiness and cleanup | [rehearsal](public-production-candidate-evidence/final-6c4bbb3-recovery-rehearsal.json), [backup](public-production-candidate-evidence/final-6c4bbb3-backup.json), [restore](public-production-candidate-evidence/final-6c4bbb3-restore.json), [replay](public-production-candidate-evidence/final-6c4bbb3-deletion-replay.json), [verified recovery](public-production-candidate-evidence/final-6c4bbb3-verified-recovery.json) |
| Edge contract (after teardown) | **Passed:** 64 Compose and 15 Caddy deliberate violations, 88-check probe, route violations, 18 limit and 5 maintenance scenarios, and 6 limit/gate mutations | campaign record |

The combined receipt's fixed scope label names working-tree transport. Here the
working tree was the clean trusted-main revision. Sampled memory and durations
describe this workstation and a tiny synthetic dataset, not VPS capacity, RPO or RTO.

**Scans at `6c4bbb3`** ([findings](public-production-candidate-evidence/final-6c4bbb3-scans.json)):
the application has none; the localizer's 63 and Keycloak's 19 unique (26 raw) inherited
HIGH/CRITICAL findings are unchanged from c7bc5cd. **Caddy is new:** one inherited
HIGH, `CVE-2026-111497` in Alpine `brotli-libs` 1.2.0-r0, fixed upstream in 1.2.1-r0.
Its govulncheck also records unfixed `GO-2026-5932`, the unmaintained
`golang.org/x/crypto/openpgp` package. None is risk-accepted here. Repository layers
add zero findings.

## Security disposition — 2026-10-25

The `6c4bbb3` receipts and scans stay unchanged. This disposition changed two image
recipes, merged in PR #372 as `9ffc05a`. Before merge, the dependency-image validator
passed every content, module, Go-advisory and scan gate on local builds; the
trusted-main publications and the [final candidate campaign](#final-candidate-campaign--9ffc05a-2026-10-25)
now supersede those builds as evidence. The [disposition record](public-production-candidate-evidence/security-disposition-20261025.json)
holds the source changes, local digests, scans, loaded-library listings and module
analysis.

- **Edge:** the same Caddy release tag, rebuilt upstream on a newer Alpine point
  release, and removal of the stock `curl` client, which neither Caddy (a static Go
  binary) nor any edge procedure uses. Its removal takes `libcurl` and `brotli-libs` with
  it. An in-place package upgrade would add versioned library paths, which the edge
  content gate rightly refuses. The builder, the limiter module and the `x/net`
  override are unchanged, and the Go module graph is identical. Gated findings drop to
  zero. A MEDIUM `go-chi/chi` finding, already present at `6c4bbb3`, stays below the gate.
- **Identity:** a supported Keycloak patch release of the same minor replaces the
  vulnerable jackson, BouncyCastle, netty and FreeMarker jars. Gated findings drop from
  19 to 10 unique. Its RPM layer is unchanged; no image of that minor ships the fixed
  `ca-certificates` or `pcre2`.
  The recipe is shared with private-dev, so the owner chose to change its deployment
  contract: private-dev promotions refuse until a separately authorized host rollout.
- **Localizer:** unchanged. Debian 12 publishes no fix for any of its 63 findings.
  Moving to another distribution is out of scope.

Runtime evidence: the trusted localizer ran with production hardening and served one
real translation. Its process had no capabilities and `NoNewPrivs=1`, and it loaded
`libssl`, `libcrypto`, `libz` and `libuuid`. It did **not** load `libsqlite3`,
ncurses/tinfo, `libsystemd`/`libudev`, `libacl`, `libblkid`, `libmount` or
`libsmartcols`. The patched Keycloak JVM rendered a Spanish login page without loading
PCRE2, font or AWT libraries. A rebuild of the edge's exact module graph does not
compile `golang.org/x/crypto/openpgp`.

| Finding group | Image | Severity | Disposition | Basis | Owner action |
|---|---|---|---|---|---|
| `CVE-2026-111497` `brotli-libs` | Caddy | HIGH | **FIXED** | Package removed with unused `curl`; no brotli or curl path in the rebuilt image | None after publication |
| jackson-core/databind (`CVE-2026-89407`, `-89425`, `-68497`, `-91776`, `-91777`), netty-handler `CVE-2026-75595`, bcprov `CVE-2026-8763`/`-13506`, FreeMarker `CVE-2026-84939` | Keycloak | 3 CRITICAL, 6 HIGH | **FIXED** | Patched jars; absent from the rebuilt scan | None after publication |
| `GO-2026-5932` `x/crypto/openpgp` | Caddy | Unrated, no fix | **NOT APPLICABLE** | Package not in the 1,030 compiled packages; govulncheck is module-level on the stripped binary | None |
| `CVE-2025-59250` mssql-jdbc | Keycloak | HIGH | **NOT APPLICABLE** | The installed `jre11` build is a listed fixed version (the scanner drops the classifier); the PostgreSQL driver is configured | None |
| OpenJDK `CVE-2026-91415` font rendering | Keycloak | HIGH, no fix | **NOT APPLICABLE** | No font or AWT library loaded while rendering pages | Re-check if server-side imaging is introduced |
| util-linux (`CVE-2026-53613`, `-53615`, `-76642`, `-78408`, `-78409`, `-78410`) across nine packages | Localizer | HIGH, no fix | **NOT APPLICABLE** | Vulnerable mount, nsenter and partition code is never run; `libblkid`/`libmount` are not loaded; setuid `mount` cannot elevate under `NoNewPrivs` with no capabilities. `libuuid` is loaded but outside these advisories | Depends on the enforced hardening |
| sqlite `CVE-2025-7458`, `CVE-2026-11822`, `-11824`; ncurses `CVE-2025-69720`; systemd-homed `CVE-2026-16742`; libacl `CVE-2026-54369` | Localizer | 1 CRITICAL, HIGH, no fix | **NOT APPLICABLE** | Libraries not loaded by the serving process; systemd-homed absent | Re-check if helper imports change |
| OpenSSL `CVE-2026-84782` DTLS; zlib `CVE-2023-45853` minizip | Localizer | HIGH, CRITICAL | **NOT APPLICABLE** | The helper serves plain HTTP with no DTLS; the minizip function is absent from `libz` | None |
| gzip `CVE-2026-41992`, perl-base `CVE-2026-9538` | Localizer | HIGH, no fix | **ACCEPT RESIDUAL RISK** | Binaries are present and the helper never invokes them. Third-party code is not audited exhaustively, so reachability is unproven, not disproved. Internal-only network, non-root, read-only, no capabilities | Approve, or require removal |
| `ca-certificates` `CVE-2026-114406` and OpenJDK `CVE-2026-91403` (eMudhra distrust) | Keycloak | CRITICAL, HIGH | **ACCEPT RESIDUAL RISK** | The vendor fix exists but no Keycloak image of this minor ships it. Outbound TLS is the SMTP relay only, so exploitation needs a network position and a certificate from a distrusted intermediate | Approve until a Keycloak image ships the fix |
| `pcre2`/`pcre2-syntax` `CVE-2026-103111`, `-86145`, `-89161` | Keycloak | HIGH | **ACCEPT RESIDUAL RISK** | The vendor fix exists but no image of this minor ships it. The JVM does not load it and `grep` uses PCRE1. `sed`, linked via `libselinux`, runs once in `kc.sh` on operator arguments; reachability is unproven | Approve until a Keycloak image ships the fix |

The three ACCEPT groups are **proposed** for owner acceptance, not accepted by this
record. The proposed scope is only the prepared, non-deployed candidate. Before any
public operation they need a renewed assessment against the then-current images,
alongside the #276 qualification.

No group is BLOCKED. The trusted-main `9ffc05a` scans confirm the same inventory: Caddy
0, Keycloak 10, localizer 63.

## Owner acceptance record

Prepared and **signed** 2026-10-25. The signature accepts only the
#275 local candidate at the identities below. It never authorizes purchase, DNS, public
exposure, private-dev change or production deployment, which keep their separate
[gates](../development/delivery-lifecycle.md#public-production-gates).

| Identity | Value |
|---|---|
| Source revision | `9ffc05a2ff30e261564ddbfd412826cdec504137` |
| Application | `ghcr.io/rubhern/videogame-platform@sha256:16e393264891ffa69e27effe6b7064598830438507f4bc6466a6b73276a22eb7` |
| Keycloak | `…-keycloak@sha256:8c245495fa2a8634d09a96158ac59a00b446076b1d65095305d7d710d32699b9` |
| Caddy | `…-caddy@sha256:12b1fae2d8aa9243c69e17c2ea427b4ee27fbd6ed7ceee5cb09ce85f272ce3ef` |
| Catalogue localizer | `…-catalogue-localizer@sha256:6c56f5397df604849d65eaa601a4581fdc191ad0388388946334be632cb60a9d` |
| Runtime fingerprint | `f41aa6308c84873f069db01e34d7f2d3be92278365c7fb73c1cb074b3ee4bb3b` |
| Model manifest | `c1f0f2c6f1b045cc712117d53ceee24c8de3dd9df4c8f7a8ed0548a04040b87f` |

| Acceptance item | Evidence state | Owner decision |
|---|---|---|
| Topology, BFF/identity, edge limits and maintenance | Passed in the `9ffc05a` campaign | Accepted |
| Trusted-image manual deployment and refusals | Passed, stages A–E and eight transitions | Accepted |
| Paired backup, isolated recovery and deletion replay | Passed synthetically | Accepted |
| Inherited image findings | [Dispositioned](#security-disposition--2026-10-25): FIXED or NOT APPLICABLE except three ACCEPT groups | Accepted with the conditions below, for the non-deployed candidate only; renewed assessment before public operation |
| Resource envelope | Reviewed hypothesis; measured on #276 | Accepted as a hypothesis |
| Real-host qualification | Deliberately out of scope | Confirmed: assigned to #276 |

- **Decision:** **Accepted.** The topology, deployment, recovery, resource-envelope and
  #276 items above are accepted as stated. The three residual-risk groups are accepted
  for the prepared, non-deployed candidate under these conditions:
  - **Localizer `gzip`/`perl-base`:** accepted while the helper never executes those
    binaries and never accepts arbitrary user-supplied files.
  - **Keycloak `pcre2`/`pcre2-syntax`:** accepted, with reassessment whenever the
    Keycloak image, its startup process or its execution paths change.
  - **Keycloak `ca-certificates`/OpenJDK eMudhra distrust:** accepted. **Before
    production opens**, a patched image or a trust-store mitigation is required,
    proven by one positive and one negative TLS test.
- **Owner and date:** Rubén Hernández, 2026-10-25.

The residual-risk conditions bind any later change to these identities. The eMudhra
condition is a gate before production opens and belongs with #276 and the public
exposure authorization; this signature does not satisfy it.

## Integration into current main — 2026-10-24

The owner confirmed that independent re-review resolved the three original P1 and
six residual findings and authorized conditional implementation integration. The
remaining P3 discovery correction now includes existing non-executable file operands
as dependencies, while recursively inspecting only executable sources. Its new
SQL/JSON regression passes with the complete **25-test provenance suite**. Static
path aliases are resolved instead of guessing unrelated root files; deliberate
negative fixtures in the runtime validator are outside its production `--env-file`
execution. The production fingerprint inventory itself is not broadened.

All staged, unstaged and untracked work/evidence was retained recoverably before
reconciling cleanly onto **`9cee33dfd2460213ea3e89711dd91bdc41405c81`**. The workflow
retains both upstream pinned image-download retries/checks and #275's production
cleanup test. No old implementation or evidence was discarded.
The [integration source/check record](public-production-candidate-evidence/integration-checks.json)
identifies this base, the P3 test bytes, local checks and the unchanged historical
runtime fingerprint. The associated PR owns the exact implementation head SHA and
its current Security/build/quality results; base checks are not PR-check evidence.

The earlier bf48897 npm-audit blocker is **resolved upstream**, not a current gate
failure: cce0bbb and 986f72e patched the vulnerable npm dependencies, and the selected
base's [Security](https://github.com/rubhern/videogame-platform/actions/runs/40081258664)
and [build/publication](https://github.com/rubhern/videogame-platform/actions/runs/40081258697)
runs passed. The old [failed-audit receipt](public-production-candidate-evidence/p1-upstream-blocker.json)
remains historical and unchanged. Independent re-review is not reopened without
new evidence; merge is conditional on the actual PR gates and final diff inspection.

**Image evidence remains revision-specific.** The c7bc5cd rehearsals demonstrate
their recorded images and operational source hashes. They do not qualify the newer
base, PR or merge revision's images. PR #371 merged as `afcf81a`; the
[6c4bbb3 campaign](#final-integrated-campaign--6c4bbb3-2026-10-24-historical) repeated
every exercise on the `6c4bbb3` publications, and the
[final candidate campaign](#final-candidate-campaign--9ffc05a-2026-10-25) on `9ffc05a`.

The owner signed the [acceptance record](#owner-acceptance-record) on 2026-10-25,
which allows #275 to close.
Purchase and public exposure remain separate owner authorizations; #276 retains the
real-host work.

## Residual independent review corrections — 2026-10-24

This section records the six independently resolved findings. Its receipts remain
unchanged and historical for their stated source hashes. That exercise base was
`bf488970c4d103608f16891715abc033dae5b8d8`; the runtime exercise uses the retained,
trusted `c7bc5cd6ac2efdb28c0f8406c11f37bb3c418916` images and unpublished operational
sources, not bf48897's changed frontend content. The
[residual-review check record](public-production-candidate-evidence/residual-checks.json)
binds that source/configuration fingerprint, code/test hashes, commands and
receipt hashes. The [final integrated recovery receipt](public-production-candidate-evidence/residual-recovery.json)
records exact image digests, rendered source/replacement configuration hashes,
migration version and disabled catalogue scheduling. Phase receipts retain the
[backup](public-production-candidate-evidence/residual-backup.json),
[restore](public-production-candidate-evidence/residual-restore.json),
[replay](public-production-candidate-evidence/residual-replay.json) and
[readiness proof before retry](public-production-candidate-evidence/residual-verified-recovery.json).
The [actual trusted-tree comparison](public-production-candidate-evidence/residual-provenance.json)
confirms that unpublished exercise configuration was refused by the expanded verifier.
Configured-but-unstarted
services are distinguished from the services actually exercised; no localizer or
full browser repetition is claimed by this focused recovery exercise.

| Residual finding | Correction and code-level proof | Integrated proof / boundary |
|---|---|---|
| P1: missing provenance sources / completeness | Include Grafana provisioning, its production runtime validator and the edge probe. An independent audit renders base plus monitoring Compose, discovers repository binds and operator-runbook commands, and follows static file operands/imports. It refuses a newly untracked executable referenced by a mount, operator command, split path, or import when the inventory omits it. 24 deployment/provenance tests pass | Shared local/trusted inventory mutations still reject drift. That exercise fingerprint was unpublished and failed trusted configuration verification; no deployment authorization was inferred |
| P2: Grafana recovery only tested a role | Render the real monitoring overlay. Notification egress is internal only in disposable configurations; production Compose is unchanged | Actual Grafana queries the provisioned PostgreSQL datasource before backup and after restore/reopen/restart. Its container is stopped before backend termination. The protected reconstruction input is compared with the actual rendered datasource secret, in addition to the SQL role/grant checks |
| P2: incomplete requested-account retry | The existing shared ledger honors an explicitly configured maintenance adapter for ordinary erasure and retry. Its exclusive ledger lock spans enter/check/mutation/proof/completion; per-step production transport fencing remains. Cleanup eligibility checks are retained. Privacy tests cover failed drain before mutation, interrupted requested-account retry, blocked concurrent retry, completion/proof and original ciphertext | Real host-loss fixture pauses after archive sealing, destroys source, restores, replays and reopens, then completes live fenced retry. The existing ledger reader verifies `completedAt` and every step; replicated bytes, suppression proof, original `archiveSha256`, both ciphertexts and safe fence release are asserted |
| Unknown LOGIN roles at reopen | Recheck the allowed pre-reopen role set, prepared transactions and subscriptions before enabling any consumer; 41 recovery tests include refusal with no login/gate mutation and the positive path | A real newly created unknown LOGIN role refuses reopen and leaves the gate closed; removing only that synthetic role allows the explicit reopen |
| Invalid Grafana password before restore | Validate protected file ownership/mode/size, UTF-8, nonempty content and embedded line/NUL separators before freshness checks or resource creation. Empty/whitespace/oversize/multiline/NUL/invalid-UTF8 negative cases assert no restore mutation | The valid credential is used by the real restarted Grafana datasource. The invalid-content cases are code-level refusal proof, not destructive restore experiments |
| Symlinked provenance directories | Reject symlink directories during traversal and symlink ancestors of explicit file inputs, rather than silently skipping them | Focused negative tests cover both a nested directory and an explicit dependency's parent. No real-host symlink layout is claimed |

The first [datasource probe failure](public-production-candidate-evidence/residual-datasource-startup-failure.json)
is retained as a failed attempt, not acceptance evidence. It failed at the first
Grafana API transport call because the readiness loop did not catch that transport
module's refusal type; the corrected bounded loop covers it without weakening SQL,
credentials or datasource assertions. Every retained final success must pass its
own resource cleanup. An [intermediate successful run](public-production-candidate-evidence/residual-recovery-generated-bytecode.json)
contained validation-generated bytecode in the fingerprinted directory. Those
files were retained recoverably outside the worktree; the final authoritative run
uses `PYTHONDONTWRITEBYTECODE=1` and the source-only fingerprint. No cache-ignore
exception was added to the fail-closed inventory. Disposable recovery directories and local Docker Desktop are
not physical independent storage or VPS-capacity evidence.

`record-recovery` is captured before live `retry` appends completion to the ledger.
That recovery receipt binds the replay's original ledger digest. The final completion
has its own ledger hash; the older proof is not relabelled with it. Later recovery
requires fresh owner reconciliation/checkpoint, as the
[canonical procedure](../../deploy/public-production/README.md#restoration-and-deletion-gate)
states. Replay continues to verify absence; the existing retry owns completion.

The source-only operational/shared-driver corrections do not change the application
schema or packaged application/image content. The optional maintenance argument
preserves operation without an adapter; production live mutations still require
its existing deny/drain authorization. No schedule, runtime deployment, provider
call, public ingress or owner risk acceptance is installed. The owner explicitly
permitted the narrow shared-ledger source fix; private-dev services/configuration
remain untouched. Static discovery covers the repository's current path/import
idioms; arbitrary generated executable paths require explicit review rather than
being claimed as automatically understood.

## Earlier independent review P1 corrections (superseded where stated)

The earlier author self-review missed three integration defects. Its pre-review
receipts remain useful for their stated seams, but do **not** establish monitoring-aware
recovery, incomplete profiled replay or the completeness of the old fingerprint.
These earlier receipts superseded those initial claims. The residual-review evidence
below further narrows what they prove: reader-role SQL login alone did not prove a
real Grafana datasource, and replay/reopen did not complete the interrupted ledger.

| Finding | Targeted correction | New evidence |
|---|---|---|
| P1: Grafana reader blocks backup / is absent at restore | Fence and terminate the optional `videogame_grafana` reader too; stop Grafana when present. Record reader presence in the pair, create it NOLOGIN before restore, reapply canonical grants/settings from an owner-only password input, and enable only known roles on verified thaw/reopen. Unknown login roles still refuse | [Recovery](public-production-candidate-evidence/p1-recovery-rehearsal.json): actual provisioned reader and live connection, drain, unknown-role refusal, thaw, fresh reconstruction, read-only grants, fresh authenticated reader session and safe reopening |
| P1: interrupted profiled erasure cannot replay | Forward the complete protected archive/key/signing/proof/holds inputs to the existing ledger, with no fresh capture or alternate erasure mechanism | [Recovery](public-production-candidate-evidence/p1-recovery-rehearsal.json), [backup](public-production-candidate-evidence/p1-backup.json), [restore](public-production-candidate-evidence/p1-restore.json), [replay](public-production-candidate-evidence/p1-deletion-replay.json), [reopening proof](public-production-candidate-evidence/p1-verified-recovery.json): real erasure workflow paused after two-copy sealing but before delete execution, host destroyed, missing-input replay refused, full replay preserves original ciphertext bit-for-bit and reopens only with evidence |
| P1: transitive runtime sources omitted | One local/trusted inventory covers imported privacy/admin/archive code, backup-common, mounted Grafana sources, reader SQL, alerts/notification templates, deployment verifier and trust client, including file modes and mounted-directory additions | [Provenance](public-production-candidate-evidence/p1-provenance.json): the then-selected trusted archive matches the earlier expanded inventory; unpublished candidate is refused. Those earlier mutation tests cover every explicit dependency, dashboard/datasource/notification changes, additions/removal, unsafe modes and stale trusted hashes |
| Race evidence overclaimed | Retain acknowledgement-loss and between-attempt tests as those narrower cases; add a native HTTP verification update genuinely in flight before the fence | [Combined exercise](public-production-candidate-evidence/p1-combined.json): withheld final body byte on native Keycloak Admin API verification update, counted in-flight request, fence held while drain waits, no premature mutation, then completed verification is preserved and cleanup reopens safely |
| Changed image assets need execution | Execute the new email test explicitly and compare source files with the current trusted image | [Email proof](public-production-candidate-evidence/p1-email.json): test ran, was not skipped; exact current email-file hashes match the immutable image |

The [first P1 combined run](public-production-candidate-evidence/p1-combined-startup-failure.json)
passed the new in-flight verification and all cleanup cases but failed the telemetry
phase before its first metric milestone; teardown passed. The harness previously
started the collector only after the long cleanup and queried unready services.
An [intermediate run](public-production-candidate-evidence/p1-combined-stopped-for-required-role-guard.json)
was deliberately stopped to retain mandatory-role failure semantics. Its teardown
receipt reported failure; explicit inspection found that run's containers/networks
removed. It is not acceptance evidence. Final runs must pass their own teardown.
The corrected harness starts Collector/Prometheus before application work and uses
bounded readiness polling for real metric/dashboard evidence, retaining failure
signatures and the failed receipt. No runtime exporter, resource limit or safety gate
is relaxed; the final combined receipt alone establishes the completed integrated pass.

**Limits:** the in-flight verification fixture uses Keycloak's native Admin API PUT on
the public HTTP listener, not a delayed browser email-token handler. Host loss pauses
the erasure workflow before invoking deletion; it does not simulate power loss inside
a PostgreSQL DELETE. Separate real uncommitted database writes in both stores and a
live Grafana reader are drained/rolled back by the backup test. The earlier recovery receipt proves reader-role/grants reconstruction. Only the
new residual-review rehearsal proves a local real Grafana datasource after restart;
real-host connection-pool/credential-rotation rollout remains #276 work. Independent storage directories simulate copies, not physical failure domains.
The existing privacy replay verifies deletion without completing the original owner
ledger. The residual-review rehearsal subsequently uses the existing fenced `retry`
to complete it and write its suppression proof; it does not change replay into a
second completion mechanism.

## Review decision and remaining gates

**Implementation (PR #371) and security disposition (PR #372) merged; the final
candidate campaign passed on `9ffc05a`; the owner signed technical acceptance with
conditions on 2026-10-25.** The
increment prepares local operations without enabling real-host deployment. Author
checks are not independent approval; the owner's signature is. Pre-purchase inputs
are a separate purchase gate. Neither merge nor campaign authorizes private-dev/production
deployment, purchase or exposure.

The [earlier focused check record](public-production-candidate-evidence/p1-checks.json)
retains its original commands, source hashes and receipt hashes. The residual-review
record remains authoritative for its historical operational source snapshot; the
integration record above identifies the P3 correction and reconciled base. Keep these distinct from runtime-test failures:

- **Dependency security:** dispositioned in the
  [security disposition](#security-disposition--2026-10-25); three residual-risk groups
  await explicit owner approval. No owner acceptance is recorded here.
- **Legal/provider:** preserve the accepted #274 decisions and R17 treatment;
  remaining processor/DPA, retention, transfer, public-claim and real supplier/media
  verification stay with their canonical owners. This audit approves none of them.
- **Contracting:** the actual SKU/stock/region/tax/term/renewal/cancellation/traffic,
  recurring email/storage costs, independent backup destination/cadence and operator
  coverage must satisfy the existing contracting gate before purchase authorization.
- **Real-host qualification (#276):** actual RAM/disk/CPU steal/I/O and model overlap,
  effective permissions/reboot/isolation, off-host failure domains and achieved RPO/RTO,
  SMTP/quotas/alerts, DNS/ACME and external IPv4/IPv6/cookies/ownership/CSRF evidence.
  Those host-only tests are not moved into pre-purchase local acceptance.

## Earlier receipts — historical, not final acceptance evidence

| Receipt | What it establishes |
|---|---|
| [Reviewed combined candidate](public-production-candidate-evidence/reviewed-candidate-20261023.json), [current cookies](public-production-candidate-evidence/reviewed-cookies-20261023.json) | **Passed on ec036c3 images and the reviewed working-tree runtime:** all seven combined checks, approved model, 15 native browser actions with zero refusals, real application/identity telemetry, Collector loss and teardown |
| [Reviewed deployment](public-production-candidate-evidence/reviewed-deployment-20261023.json), [nine attempt receipts](public-production-candidate-evidence/reviewed-deployment-attempts-20261023.json) | **Passed on ec036c3:** trusted configuration/digests, real migration/TLS/issuer/refusals, approved model, eight transitions after initial deployment; migration/readiness/smoke failures detected, synthetic state preserved, teardown passed. Browser explicitly omitted here and covered by the combined receipt |
| [Reviewed host-loss rehearsal](public-production-candidate-evidence/reviewed-recovery-rehearsal-20261023.json), [backup](public-production-candidate-evidence/reviewed-backup-20261023.json), [restore](public-production-candidate-evidence/reviewed-restore-20261023.json), [deletion replay](public-production-candidate-evidence/reviewed-deletion-replay-20261023.json), [verified recovery](public-production-candidate-evidence/reviewed-verified-recovery-20261023.json) | **Passed on ec036c3:** real paired encryption, in-flight write rollback, source-runtime destruction, reconstruction from copied pair, missing-evidence startup refusal, protected deletion replay, old offline-token refusal, readiness and cleanup. Independent directories simulate storage, not physical failure domains |
| [Review/source/check receipt](public-production-candidate-evidence/reviewed-checks-20261023.json) | Exact base SHA, changed executable hashes, every production-service image reference, Docker engine boundary, reproduction instructions, author review, focused checks and the expected unpublished-configuration refusal. Independent approval and current-change CI remain pending |
| [Reviewed edge scenarios](public-production-candidate-evidence/reviewed-edge-20261023.json) | **Passed:** nine real-edge rate/household/aggregate scenarios; serial execution after the combined stack, because their fixed subnets cannot coexist |
| [Pre-P1 scan findings](public-production-candidate-evidence/reviewed-scans-20261023.json), [previous 101ce2e findings](public-production-candidate-evidence/current-main-scan-review.json) | Exact scan hashes and applicability backlog; that historical capture reports 63 localizer and 18 Keycloak, with no owner acceptance |
| [Historical combined candidate, 39b57c5](public-production-candidate-evidence/final-candidate.json), [cookie inventory](public-production-candidate-evidence/cookie-inventory.json), [approved model](public-production-candidate-evidence/model-selection.json) | **Passed:** approved model translation; all browser flows; cleanup dry-run, lost-acknowledgement interruption, fresh-verification race, archive/proof and reopening; real app/identity telemetry and six dashboards; Collector loss; no observed runtime restart/OOM; teardown. Browser recorded 15 native actions and zero 429 refusals |
| [Baseline browser rejection](public-production-candidate-evidence/baseline-browser-rejection.json), [BFF rejection](public-production-candidate-evidence/bff-navigation-rejection.json) | Retained failures behind local calibration: native action-link 429 on the old broader identity budget, then product-origin navigation 429 with 27-second Retry-After after the two-account test passed. These are not successful baseline acceptance |
| [Cleanup before pool fix](public-production-candidate-evidence/cleanup-before-pool-fix.json) | All four cleanup checks passed, including real revocation with lost acknowledgement, newly verified retry refusal, archive verification and reopening. The overall run is deliberately retained as **failed**: subsequent monitoring startup exhausted the old address pool; teardown passed |
| [Deployment baseline](public-production-candidate-evidence/deployment-baseline.json), [all attempt receipts](public-production-candidate-evidence/deployment-attempts.json) | **Passed A/B/D/E:** same-image Flyway and approved-model translation; eight deployment transitions (plus initial boot) detected missing-database migration, stopped-readiness and injected-smoke failures, preserved the sentinel, recovered successfully, and tore down. Browser explicitly omitted in this focused trusted-baseline run; the final combined candidate supplies its independent browser evidence |
| [Host-loss rehearsal](public-production-candidate-evidence/recovery-rehearsal.json), [encrypted-backup receipt](public-production-candidate-evidence/backup.json), [verified synthetic recovery](public-production-candidate-evidence/verified-recovery.json), [restore](public-production-candidate-evidence/restore.json), [deletion replay](public-production-candidate-evidence/deletion-replay.json) | **Passed:** paired encryption, original-runtime destruction, isolated reconstruction from the copied pair, rollback of in-flight writes, deletion replay, old offline-token rejection, evidence-gated readiness and cleanup. Two directories simulate independent storage; they do not prove actual Lenovo/media failure-domain isolation |
| [Focused edge scenarios](public-production-candidate-evidence/edge.json), [local checks and source hashes](public-production-candidate-evidence/local-checks.json) | **Passed:** nine actual-edge boundary, shared-household and aggregate-capacity scenarios; static mutation checks, privacy/lease/drain/deployment tests, workflow classification/lint and focused frontend/portal checks. The checks record contains the focused edge reproduction source and full-suite alternative |
| [Monitoring overhead](public-production-candidate-evidence/observability.json) | Separate bounded fixture measures dashboard/query/rule work and resource use; it does not establish real-VPS headroom |
| [Scan review](public-production-candidate-evidence/scan-review.json) | Exact retained scan hashes and package findings; no owner risk acceptance |

## Issue acceptance matrix

| #275 criterion | Implementation and verified evidence | Incomplete / #276 boundary |
|---|---|---|
| Versioned, independent topology exercised without host exposure | **Verified locally on `9ffc05a`:** production Compose, separate databases/roles, realm, secrets, volumes, approved model and combined monitoring; final combined receipt | Actual host filesystem, secret permissions, reboot and external isolation: #276 |
| BFF, ownership, CSRF, redirects, proxy trust and management denial | **Verified locally on `9ffc05a`:** trusted identity/routing CI, final edge contract and browser flows in both the combined and deployment exercises; earlier edge/TLS receipts remain historical | Real-domain cookie re-inventory, actual mail, external ownership/CSRF journey: #276 |
| Exact trusted images, migration, smoke and manual promotion/refusals | **Verified locally on `9ffc05a`:** the manual `--rehearsal` procedure accepted the trusted configuration and publication digest, migrated, and passed stages A–E with eight transitions; 25 transition/refusal/provenance tests. Earlier ec036c3 transitions are historical | Owner acceptance. Real-host adapter remains refused |
| Encrypted backups, isolated restore and reconstruction | **Verified synthetically on `9ffc05a`:** real Grafana datasource backup/reconstruction, GPG/fault tests and profiled host-loss replay plus completed retry; in-flight writes rolled back, deletion replay and old offline-token rejection passed; missing replay evidence refuses reopening | Real independent destination/access, host-loss recovery, deletion replay, achieved RPO/RTO and schedule: #276 |
| Explicit bounded resource/job envelope | **Reviewed hypothesis:** canonical envelope; 640 MiB monitoring contract; sampled monitoring receipt | Nominal headroom is insufficient to assert >=20% available memory. Helpers/scratch and workload overlap must be measured on #276; no automatic expansion |
| Current-source CI, gates and owner review | **Merged and rehearsed:** PR #371 and #372 checks passed; trusted-main `9ffc05a` build/publication and Security passed; final candidate campaign passed on its images | Owner signed 2026-10-25 with residual-risk conditions; contracting prerequisites gate purchase |
| No purchase/DNS/private-dev deployment | **Preserved:** no host deployment, real credentials/accounts, purchase, DNS, ingress or timer installation; the final campaign ran only on local Docker beside untouched unrelated workloads | Real-host work requires separate authorization; private-dev retirement remains separate |

## Pre-purchase gate audit

| Canonical gate | Local/CI evidence or implemented preparation | Remaining decision / host-only proof |
|---|---|---|
| Source/release | Trusted image resolver and scan/publication inventory; [`9ffc05a` campaign record](public-production-candidate-evidence/final-9ffc05a-campaign.json) | Trusted-main `9ffc05a`: localizer **63**, Keycloak **10**, Caddy **0** inherited HIGH/CRITICAL findings, application zero; zero repository-layer additions. The [disposition](#security-disposition--2026-10-25) fixes or evidences all but three groups, which need explicit owner approval, and a renewed assessment before public operation |
| Provider/media | #274 records and approved provider-independent local data path | Preserve pending provider/privacy obligations; actual permitted acquisition/CDN behaviour in #276; no load against IGDB |
| Accounts/privacy | Existing protected ledger/archive; live transport, conditional identity deny/drain, role exclusion, 30-day filter and suppression proof; focused race/fault tests | Final legal/supplier/publication claims remain with their canonical owners. Actual protected storage, admin privilege, SMTP delivery/quotas, backlog and daily cadence: #276; no timer installed |
| HTTPS/isolation | Synthetic CA, same-origin BFF, strict hosts/paths, private management; edge contract tests | Real DNS/ACME/renewal and IPv4/IPv6 external denial, forwarding/source identity, TLS persistence and cookie inventory: #276 |
| Secrets | Consumer-specific files; empty/missing inputs refused; independent generated rehearsal secrets | Registry visibility/read-only pull credential selection, actual private host permissions, rotation/reconstruction and break-glass acceptance: #276 |
| Capacity/abuse | Explicit budgets; per-source/aggregate rejection and maintenance CI; optional API admission stays off | Calibrate shared-IP/IPv6, actual workload, CPU steal, pools/I/O and recovery. Application admission values remain experimental. Measured RAM/disk/restart requirements: #276 |
| Backup/recovery | Paired encrypted dump, checksums, bounded retention/copy, replay/reopen gates and synthetic reconstruction | Lenovo is selected, but concrete second-copy media/location, independent failure domains, copy/check cadence and operator coverage must be recorded privately. Actual isolation, restore and RPO/RTO: #276 |
| Observability/operation | Real provisioned dashboard/query/notification mock exercise and bounded telemetry; canonical bootstrap/incident procedures | Alert thresholds, real channel, complete host-loss notification, filesystem/cgroup mapping and retention/compaction soak: #276 |
| Contracting review | Accepted architecture; portable candidate and explicit remaining checklist | Owner diff/recovery acceptance; actual quote/SKU/region/tax/term/renewal/cancellation/traffic/DPA and all recurring backup/email costs within the approved budget. No service contracted or legal risk approved by this audit |

## Failure and resource evidence

| Scenario | Evidence seam | Required behaviour |
|---|---|---|
| Telemetry loss | Baseline `TelemetryFailureIsolationIntegrationTest`, production telemetry fixtures and combined rehearsal | Product stays available; missing data alerts instead of fabricated zero health |
| Capacity rejection | Production-runtime CI, aggregate API/identity scenarios | Bounded 429/503, Retry-After, independent sources and shell availability; actual thresholds await #276 |
| Interrupted identity maintenance | `test-production-identity-maintenance.py` drain-timeout/ownership tests and cleanup rehearsal | Persist fence until proved drain; wrong run cannot release; lost acknowledgement/retry cannot delete newly verified account |
| Missing recovery evidence | `test-production-recovery.py` missing/stale/changed replay/checkpoint cases | No reopening or fabricated readiness |
| Failed deployment | `test-public-production-deployment.py` and integrated transition receipts | No unsafe activation; failed candidate stopped; schema-aware forward recovery, no implicit rollback |
| Backup/encryption/copy failure | `test-production-recovery.py` interrupted dump/encryption/copy cases | No published partial pair/plaintext; gate stays closed and thaw refuses incomplete evidence |

The historical monitoring sample is a short workstation fixture: 1,207 OTLP series, six
dashboards, 113 PromQL panels refreshed four times and 33 SQL panel queries. Grafana
reached 320 MiB and Prometheus approached 96 MiB, with no observed OOM/restart.
Those ceilings need explicit real-host scrutiny. The sampler's conservative
parent/child peak was about 56.2 MiB against its 64 MiB reserve. See the
[exact sampled values](public-production-candidate-evidence/observability.json).
These are not sustained RSS, capacity or latency guarantees.

The canonical envelope totals 4.50 GiB including monitoring and the localizer,
plus 0.50 GiB serialized actor and 1 GiB host allowance. At nominal 7.45 GiB usable
RAM that leaves ~1.45 GiB, slightly below 20%, before lease/client helpers and
volatile backup scratch. This conservative sum is not actual overlap: deployment
stops the application before Flyway, and backup stops consumers before dumping.
Pausing localization requests leaves model memory resident; deliberate unloading
requires checked restart before acceptance. Stop heavy sync/localization for maintenance; account
for the 96 MiB helpers and off-VPS encryption/scratch. The recovery lease does not
schedule catalogue work. #276 must stop/recalibrate on headroom loss, ceiling contact,
OOM/swap, disk <25% free, pool saturation or failed recovery evidence; no capacity
claim or automatic paid upgrade follows from this audit.

## Reproduction and remaining acceptance

Run the commands in the [runtime exercise](../../deploy/public-production/README.md#disposable-integration-exercise),
[cleanup procedure](../../deploy/public-production/README.md#identity-maintenance)
and [host-loss exercise](../../deploy/public-production/README.md#paired-backup-and-recovery-candidate)
with the intended exact trusted source and a new evidence directory each time.
The final candidate campaign selects `9ffc05a2ff30e261564ddbfd412826cdec504137`; the
`6c4bbb3` record holds the exact command forms. Earlier receipts (`6c4bbb3`, `c7bc5cd`,
`ec036c3`, `39b57c5`) remain historical evidence. Supply
the existing approved checksum-verified model; never download a substitute.
The browser helper now pins the available Ubuntu trust-tool package and checksum;
its former exact package had disappeared. The combined candidate exposed telemetry
address-pool exhaustion; the private pool now admits all configured consumers plus
bounded probes while preserving the fixed Caddy address, with a focused regression
assertion. The native browser journey also exposed a shared-source action limit that rejected
its second signup with 429. The broader identity-request and BFF-navigation budgets are recalibrated, retaining
the original action and separate mail-sending limits, with explicit boundary and
shared-household tests. A later recovery-account start had also hit the BFF quota
after the successful two-account journey; its sanitized Retry-After evidence identified
that separate source budget. The browser test
now completes the existing welcome screen, rather than relying on the transient
root URL. These fixes do not change the approved architecture. Browser journeys have a finite 120-second
per-test budget for two signup/email flows under configured CPU limits, with zero retries.

Selective CI includes the transport refusal tests in the existing production-runtime
job; classification tests explicitly keep the new scripts out of unrelated frontend,
backend and identity suites. The registry-authenticated rehearsals remain local,
not another deployment workflow. Administrative scripts have no independent release
version; backend, frontend, OpenAPI and PoC artefacts are unchanged, so no version bump.

The signed [acceptance record](#owner-acceptance-record) allows #275 to close. Assign the real-host tests above to #276, retain the separation
between authorization to purchase and authorization to expose, and keep private-dev
untouched.


Documentation validation passed in a clean source-review snapshot. The ambient
validator traverses unrelated ignored `.claude/worktrees` and reports their vendored
links; those worktrees were left untouched. Portal builds use a temporary Git index
to include the reviewed new sources/assets without staging this working tree. The
production-preview candidate page was found through its sidebar and search; focused
navigation/accessibility and 320/834px reflow tests passed, with 1320/390px candidate
page inspection. The residual-review portal build and focused navigation/search
checks validate the revised record and its evidence assets. No unrelated application suite is repeated locally.
