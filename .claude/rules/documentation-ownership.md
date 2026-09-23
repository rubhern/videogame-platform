---
paths:
  - "docs/**/*.md"
  - "README.md"
  - "backend/README.md"
  - "frontend/README.md"
  - "AGENTS.md"
  - "CLAUDE.md"
---

# Documentation ownership

Hard constraints for documentation changes. The canonical ownership table is
[`docs/README.md`](../../docs/README.md); it decides every question this rule does
not answer.

## Never

- Write a fact into a second document when
  [`docs/README.md`](../../docs/README.md) names another canonical owner. Link to the
  owner and keep only the local context that document needs.
- Restate in prose what an executable source owns: OpenAPI owns HTTP shapes, Flyway
  SQL owns the schema, Maven and npm manifests own exact versions, and scripts,
  Compose, Dockerfiles, configuration, and workflows own their mechanics.
- Put patch-level dependency versions, command internals, generated schema detail, or
  CI job mechanics in prose.
- Create a document because a topic exists. A new document needs a distinct
  long-lived responsibility, an intended consumer, a canonical owner, and a reason it
  cannot fit in an existing source.
- Preserve issue timelines, completed checklists, point-in-time reviews, CI
  transcripts, or `Last verified` chronicles as evergreen documentation.
- Present approved future behaviour, current implementation, and historical evidence
  as one current state.
- Promote a deferred story-map idea or a Vision PDF capability into a current
  requirement. The approved product records narrow the PDF, never the reverse.
- Resolve a contradiction between two authoritative sources silently. Report it.

## Always

- Update the canonical owner and delete the text the change made obsolete.
- Update inbound links, indexes, `AGENTS.md`, scripts, and workflows after moving or
  deleting a file.
- Preserve durable constraints, invariant identifiers, evidence limitations, accepted
  owner exceptions, and decision-reopening conditions.
- Keep the status marker (`- **Status:** Approved` / `Accepted`) intact on the
  documents that carry one; `scripts/validate-docs.sh` checks it.
- Run `bash scripts/validate-docs.sh` after any documentation change, and always
  after moving, deleting, or relinking a file.
