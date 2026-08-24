# Delivery lifecycle

- **Status:** Approved
- **Owner:** Ruben Hernandez
- **Scope:** Private, non-commercial learning MVP operated by one person

This document owns the human change flow, risk, review, validation selection,
versioning, acceptance, and Definition of Done. The
[platform design](../architecture/deployment/mvp-platform-and-delivery.md) owns
environment, artefact, migration, deployment, backup, and recovery mechanics.

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
- Public production remains prohibited until provider, privacy, security, support,
  cost, and release-mode gates are reopened and approved.

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
