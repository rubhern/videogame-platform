---
name: validate
description: Select the smallest local validation set that can detect a regression in a VideoGame Platform change, using the risk-based policy in the delivery lifecycle. Use before reporting a change complete, when unsure which script or suite to run, or when tempted to run the full pipeline locally. Do not use to design or implement the change itself.
---

# Validate

Choose validation by risk and affected area, not by habit. Running every gate is a
failure of selection, not thoroughness.

`docs/development/delivery-lifecycle.md` owns the validation policy and
`docs/development/continuous-integration.md` owns the command catalogue. This skill
only applies them; it never redefines a gate.

## Workflow

1. List the changed paths (`git status --porcelain`, `git diff --name-only`).
2. Map each path to an affected area. When unsure, ask the executable classifier
   instead of guessing:

   ```bash
   git diff --name-only main...HEAD | bash scripts/detect-ci-changes.sh --paths-from-stdin
   ```

   Its `documentation`, `frontend`, `backend`, `migrations`, `identity`, `browser`,
   `container`, and `openapi` outputs are the same areas CI selects.
3. Name the concrete regression each candidate check could detect. Drop any check
   with no such regression.
4. Run the smallest remaining set.
5. If a check fails, narrow the reproducer before running anything broader.
6. Report what was run, what passed, and what was deliberately left to CI.

## Minimum check by area

`docs/development/continuous-integration.md` owns the gate catalogue. The table
below is the narrowed local selection derived from it: prefer the smallest command
that still fails on a related regression. When a script is renamed, that document
and this table change together.

| Changed area | Minimum local check |
|---|---|
| Documentation, `AGENTS.md`, `CLAUDE.md`, `.claude/**`, `.agents/**` | `bash scripts/validate-docs.sh` |
| CI classification or workflows | `bash scripts/test-ci-change-detection.sh` and `bash scripts/validate-actions.sh` |
| OpenAPI contract | `bash scripts/validate-openapi.sh`, then the affected consumer generation |
| Backend Java | `./mvnw -pl backend test` for the affected tests; `./mvnw clean verify` only when the module boundary or build changed |
| Flyway migration | `bash scripts/validate-migrations.sh` |
| Frontend | `npm run frontend:verify` |
| Packaged browser journey | `bash scripts/validate-browser.sh` |
| OIDC/BFF session | `bash scripts/validate-identity.sh` |
| Dockerfile or image | `bash scripts/validate-container-image.sh` |

## Broaden only for a stated hypothesis

Add a broader check only when one of these holds, and say which one:

- a concrete failure crosses a boundary the focused check cannot observe;
- the change touches shared build, runtime, CI, or validation infrastructure;
- a migration is high risk or destructive;
- a reported failure needs local reproduction;
- CI is unavailable or cannot cover the change;
- a critical release is being prepared;
- the owner asked for it.

## CI evidence

Green required checks on the current commit are valid evidence. Do not reproduce the
pipeline locally to duplicate them. If the checks predate the current `main`, rebase
and use the new run rather than compensating with an exhaustive local sweep.

Never turn an unreliable test green by retrying. Diagnose it, or record a waiver with
the failed control, reason, risk, compensating control, owner, and expiry.
