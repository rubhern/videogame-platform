# Delivery lifecycle

- **Status:** Approved
- **Owner:** Ruben Hernandez
- **Scope:** Current private delivery and gated public, non-commercial evolution operated by one person

This document owns the human change flow, risk, review, validation selection,
versioning, acceptance, and Definition of Done. The
[platform design](../architecture/deployment/mvp-platform-and-delivery.md) owns
environment, artefact, migration, deployment, backup, and recovery mechanics.

This lifecycle applies to post-MVP evolution. The
[Product Brief](../product/product-brief.md#public-release-and-sustainability) owns
owner-approved release direction; the separate public-production gates below apply.

## Change flow

```text
need -> ready -> implement and validate -> review -> merge -> validate/accept -> done
```

A change is ready when its outcome, in/out scope, acceptance criteria, dependencies,
highest risk, and affected product/architecture sources are clear. Significant
durable choices require an ADR; bounded experiments require a hypothesis, scope,
success/failure criteria, evidence, and adopt/change/reject decision.

| Risk | Typical change | Required emphasis |
|---|---|---|
| Low | Documentation or behaviour-preserving internal correction | Focused review and affected checks |
| Medium | Endpoint, dependency, non-destructive migration, authentication flow | Explicit failure and recovery approach |
| High | Destructive data, authorization, secrets, topology | Reviewed design/ADR, recovery evidence, owner go/no-go |
| Emergency | Active credential leak, data corruption, critical outage | Contain first; retain minimum validation and traceability |

Use short-lived branches from current `main` and focused English commits. Material
changes use a pull request describing scope, compatibility/version impact, API/data/
security/accessibility/operations impact, validation, and residual risk. Required
checks must pass and the owner reviews the complete diff. AI assistance is a second
pass, not independent approval.

## Semantic Versioning

Assess each releasable artefact independently: backend Maven reactor, frontend npm
package, OpenAPI contract, and isolated IGDB PoC.

For pre-`1.0.0` artefacts:

| Change | Increment |
|---|---|
| Compatible fix or internal/security correction | `PATCH` |
| New compatible capability or material enabler | `MINOR` |
| Intentional incompatible contract change | `MINOR` plus explicit compatibility decision and migration notes |

After `1.0.0`, incompatible public/supported operational contracts use `MAJOR`,
compatible capabilities `MINOR`, and compatible fixes `PATCH`. Documentation-only
changes do not bump executable artefacts unless they alter release content.

Development Maven versions use `-SNAPSHOT`. A named release removes the suffix and
uses the matching `vX.Y.Z` tag. Backend modules inherit the root reactor version;
parent and reactor versions change atomically.

## Validation policy

1. Identify affected behaviours, files, contracts, consumers, and runtime boundaries.
2. Run the smallest local check that can detect a related regression.
3. Add broader validation only for an explicit cross-boundary failure hypothesis.
4. Diagnose failures with the narrowest reproducer before expanding scope.
5. Use pull-request CI as authoritative affected-area evidence and trusted `main` CI
   as full integration evidence for the commit tested.

Documentation uses `validate-docs.sh`; OpenAPI adds contract validation and affected
consumer generation; frontend/backend/data/identity/container changes use their
focused gates. Dependency updates validate the affected ecosystem and rely on fresh
selective CI. Broaden locally for shared build/CI infrastructure, major runtime
changes, high-risk migrations, unavailable CI, critical releases, or explicit owner
request. The [CI guide](continuous-integration.md) maps commands.

Retries may collect diagnostics but must not turn an unreliable test into a pass. A
temporary waiver records the failed control, reason, risk, compensating control,
owner, and expiry/removal condition.

## Contract, data, and release rules

- Change OpenAPI before or with implementation and generated artefacts; update
  Postman in the same API change.
- Use immutable forward migrations and explicit compatibility/recovery for data.
- Rotate/revoke an exposed secret before repository cleanup.
- Review dependency purpose, compatibility, licence, security, and rollback.
- A merge or published image is not product acceptance. A named private release
  records version/tag, accepted journey evidence, known limits, and image digest.
- Private-dev application promotion requires an explicit owner dispatch on main after
  trusted-main evidence; dispatch is the sole human deployment approval. The dev
  environment holds secrets and restricts the branch without a second reviewer gate.
  Successful deployment automation
  records technical smoke evidence; the owner still accepts the product/release.
  Separate runtime/dependency rollouts, including the required private-dev catalogue
  localizer, require their own reviewed operational decision and applied-runtime
  acknowledgement before application promotion;
  see the [platform boundary](../architecture/deployment/mvp-platform-and-delivery.md#artefact-and-delivery).
- The owner-approved public-release direction does not authorize contracting or
  exposure. Apply the [public-production gates](#public-production-gates); no service,
  DNS/ingress or existing infrastructure is changed during the #252 investigation.

## Public production gates

**Proposed acceptance criteria for owner review (#252); no gate is passed here.**
The [Product Brief](../product/product-brief.md#public-release-and-sustainability)
owns the approved open/non-commercial/best-effort direction, total recurring budget
and deferred purchase. The [platform target](../architecture/deployment/mvp-platform-and-delivery.md#public-production-target)
owns the technical proposal; [ADR-0025](../decisions/0025-host-public-production-on-a-deferred-single-vps.md)
awaits technical-design acceptance. Design review, purchase and opening ingress
are distinct owner decisions. Pre-purchase criteria exclude real-host-only evidence.

| Requirement | Prepare without VPS: local/CI and owner review | Prove on selected real VPS before exposure |
|---|---|---|
| Source/release | Reviewed production contract/configuration, ADR and candidate digest with current trusted-main checks/scans/provenance; compatible migrations and recovery plan | Deployed source/digests/config fingerprint, migration/readiness/smoke receipt; previous digest and assessed rollback/forward recovery |
| Provider/media | Terms/attribution/derivative/media assessment and fallback; resolve or disable disputed media; bounded sync and no request-path provider access | Credentialed acquisition at permitted rates, provenance/attribution and public CDN/fallback behaviour |
| Accounts/privacy | Minimum profile, legal basis/notice/contact/age/retention and rights procedure; synthetic deletion/export/restore tests, including interrupted and repeated deletions; immediate revocation of a disabled/deleted account's open session; SMTP route/terms/budget selected; verified signup/reset and abuse tests, including no email enumeration at registration | Applicable processor agreement before personal data; actual email/recovery, two-account ownership/CSRF/session evidence, no local seed users |
| HTTPS/isolation | Static proxy allowlist/trust model, production realm/client/origins, [canonical issuer contract](../architecture/deployment/mvp-platform-and-delivery.md#canonical-issuer-contract), networks and denied management routes tested with local HTTPS | DNS/ACME issuance and renewal path, certificate-state persistence; independent public IPv4/IPv6 checks; private SSH/admin/telemetry; no DB/management/Docker ports; spoofed-host/header rejection; issuer contract check and browser cookie/storage re-inventory with the real hostnames before the first account |
| Secrets | Consumer-specific file permissions, rotation/reconstruction and independent production-secret plan; no real secrets in CI/image/repo | Effective protected-file permissions, service/file rotation consistency, private break-glass admin and independent reconstruction material |
| Capacity/abuse | Representative fixture/workload, resource/job budgets and stop criteria; controlled overload and signup/login limits tested | CPU steal/I/O/memory/network and bounded qualification, cold-model/new-game sample, recovery, >=20% available memory and >=25% free disk, no OOM/swap thrashing; restart/reboot behaviour |
| Backup/recovery | Paired encrypted backup/retention logic, isolated restore tests and reconstruction rehearsal; independent destination/copy/check cadence selected | Verified off-host pair, identity/rating consistency; actual host-loss reconstruction/restore, deletion reapplication and achieved RPO/RTO |
| Observability/operation | Safe bounded telemetry/log retention and private notifications; update/incident/backup-age procedures; best-effort limitations/contact | Private dashboards, failed-backup/disk/error/certificate notification, redacted diagnostics, closing signup/edge and stop-sync controls |

**Contracting gate:** owner reviews the diff and accepts the technical proposal;
preparation produces a production candidate, not merely a green image build.
Affected-area/current-source CI and local candidate exercises pass without deploying
to existing private-dev. Record the remaining real-host-only checks. Recheck the
selected SKU/architecture/stock/region, tax-inclusive price, minimum term/billing/
cancellation/renewal, traffic and processor terms. Sum recurring items at renewal
prices with capped overages within the approved total budget; record cash commitment
and contingency and workable backup/email routes. Only then may the owner explicitly
authorize purchase. This work does not grant that authorization.

**Exposure gate:** after authorized purchase, establish private administration and
qualify the candidate with synthetic data/restricted ingress. ACME/external tests may
need temporary, separately owner-approved public test routes; restrict them and deny
real registration/data until acceptance. Record real-host evidence in the operations
runbook, resolve failures, approve achieved recovery/operation limitations and
provider/privacy acceptance, then obtain the owner's public go/no-go. Local/CI passes
or deployment success are insufficient. Failure keeps production closed; no automatic
upgrade, provider change or fallback exposure of private-dev.

Public acceptance exercises the primary journey from an external network:
browse/search/detail, verified registration/sign-in, create/edit/delete a rating,
retrieval, logout and recovery. Reassess versions for actual implementation;
#251's milestone is planning, not permission to change current artefact versions
in this documentation spike. Private-dev retirement is separately considered after
launch, with protected-state/evidence preservation.

## Definition of Done

- [ ] Acceptance criteria and approved scope are satisfied.
- [ ] Boundaries and important invariants remain clear and tested.
- [ ] Contracts, migrations, configuration, and documentation changed together when affected.
- [ ] Semantic Versioning impact is assessed and references are consistent.
- [ ] Security, privacy, accessibility, provider, and operational impacts are addressed.
- [ ] Relevant local checks and required current-commit CI evidence pass.
- [ ] The full diff has received a fresh review.
- [ ] Artefacts and, when applicable, deployment are traceable to source.
- [ ] Recovery is credible for the change risk.
- [ ] Known limitations and follow-up work are recorded without duplicating the backlog.
