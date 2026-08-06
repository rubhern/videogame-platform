# ADR-0013: Use model-backed and purpose-specific architecture diagrams

- **Status:** Accepted
- **Date:** 2026-08-06
- **Owner:** Ruben Hernandez
- **Scope:** Architecture documentation for the private, non-commercial learning MVP
- **Related architecture:** [Learning MVP solution architecture](../architecture/mvp-solution-architecture.md)
- **Diagram catalogue:** [Architecture diagrams](../architecture/diagrams/README.md)

## Context

The approved MVP now has enough product, domain, application, deployment, delivery,
and technology definition to benefit from an initial architecture diagram baseline.
The views need to serve several different questions:

- the shared C4 system, container, and private-dev deployment structure;
- strategic domain boundaries and dependency rules;
- authentication, rating, catalogue-synchronization, and delivery behaviour;
- a polished deployment view for presentations and rapid visual review.

No single diagramming tool represents all of these views equally well. Using several
tools without explicit ownership, however, can create conflicting models, duplicated
maintenance, generated-file churn, and diagrams that silently introduce decisions
not present in approved sources.

The project needs a durable authority order, a clear purpose for each tool, and a
small initial catalogue that remains subordinate to the approved architecture.

## Decision

Use a model-backed and purpose-specific diagram strategy:

1. Approved architecture documents, domain documents, API contracts, and ADRs own
   architectural decisions.
2. Structurizr DSL owns the canonical shared C4 model and its System Context,
   Container, Deployment, and future model-backed C4 views.
3. Mermaid owns focused diagrams-as-code for domain relationships, dependency rules,
   sequences, states, and delivery flows that are not already owned by an approved
   Markdown document.
4. Mermaid diagrams embedded in an approved Markdown document remain owned by that
   document and are not duplicated into the standalone catalogue by default.
5. diagrams.net is reserved for deliberately polished, manually laid-out derived
   communication views. It must not introduce a component, relationship, protocol,
   or deployment decision absent from an authoritative source or the Structurizr
   model.
6. Editable sources are committed. Generated SVG, PNG, and PDF exports are ignored by
   default and committed only when a repository consumer requires them and drift can
   be validated.

Store the sources under:

```text
docs/architecture/diagrams/
├── structurizr/
├── mermaid/
├── diagrams-net/
├── generated/
└── scripts/
```

Do not recreate every view in every tool. Duplication is permitted only for a
temporary comparison or a clearly labelled derived communication view.

Every diagram change must identify its owning decision source, use approved
terminology, preserve the current MVP boundary, and receive visual review in addition
to syntax validation.

## Alternatives considered

### Use Structurizr for every diagram

This would maximize model reuse and C4 consistency, but sequence, domain, dependency,
and delivery views would be less direct to author and review. It would also force
behavioural views into a tool selected primarily for architecture modelling.

### Use Mermaid for every diagram

This would provide a small text-based toolchain and good Markdown integration, but it
would lack one shared C4 model and make polished manual communication layouts harder
to control.

### Use diagrams.net as the only source

This would offer strong visual control and broad editor support, but relationships
would be maintained independently in every drawing. Semantic drift and review of
model changes would be harder to detect.

### Commit generated exports for every source

This would make rendered assets immediately available, but it would increase binary
churn and create a second set of files that can drift from editable sources before a
repository consumer actually requires them.

## Consequences

### Positive

- C4 views share one explicit model.
- Focused diagrams remain readable as text and easy to review in diffs.
- The polished deployment view can optimize communication without becoming an
  architectural authority.
- The authority order makes conflicts and drift visible rather than silently choosing
  one drawing.
- Generated assets do not add routine repository churn.

### Negative

- Contributors must understand three tools and select the correct one.
- A derived diagrams.net view requires deliberate reconciliation with Structurizr and
  approved documents.
- Manual Structurizr layout requires committing `workspace.json` alongside the DSL.
- Visual correctness still requires rendering; source validation alone is
  insufficient.

## Risks and mitigations

- **Cross-tool drift:** keep an explicit catalogue with an owning source for every
  standalone view and review derived views against Structurizr.
- **Duplicate diagrams:** keep existing embedded authoritative Mermaid views in their
  documents and reject copies without a distinct question.
- **Accidental generated files:** ignore tool state, editor backups, and generated
  exports; stage diagram sources explicitly.
- **Unreadable rendered output:** render every changed source and inspect text,
  connectors, clipping, hierarchy, and contrast before merge.
- **Speculative architecture:** require every element and relationship to trace to an
  approved source or a separate proposed decision.

## Follow-up actions

- Keep the catalogue current when adding, renaming, superseding, or removing a view.
- Validate Structurizr and Mermaid syntax in CI after their local commands are stable
  and repeatable.
- Add generated-output drift checks only if committed exports become documentation
  dependencies.
- Revisit this ADR if one tool can no longer express its assigned views, collaboration
  needs change, or diagram maintenance becomes disproportionate.
