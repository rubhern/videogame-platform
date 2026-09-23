# ADR-0013: Use model-backed and purpose-specific architecture diagrams

- **Status:** Accepted
- **Date:** 2026-08-06
- **Owner:** Ruben Hernandez
- **Scope:** Architecture documentation

## Context

No one diagramming tool represents shared C4 structure, focused behavior and polished
manual communication equally well. Multiple tools without ownership create drift and
duplicate models.

## Decision

- Architecture documents, contracts and ADRs own decisions; diagrams visualize them.
- Structurizr DSL owns the shared C4 model and model-backed C4 views.
- Mermaid owns focused sequence, state, dependency, domain and delivery diagrams not
  already owned inline by another approved document.
- diagrams.net is reserved for deliberately polished derived views and may not
  introduce decisions absent from an authoritative source.
- Commit editable sources. Commit generated exports only when a repository consumer
  needs them and drift can be checked.
- Do not recreate every view in every tool. A duplicate requires a distinct audience
  or question and explicit derivation.

The current inventory, source paths and validation commands belong to the
[diagram catalogue](../architecture/diagrams/README.md).

## Alternatives considered

- **One tool for every view:** rejected because each candidate was materially weaker
  for at least one required view type.
- **Commit every generated export:** rejected because it creates binary churn and a
  second drift surface without a consumer.

## Consequences

C4 views share a model while focused diagrams remain diffable and polished derived
views remain possible. Contributors must select among three tools, render for visual
review and reconcile any derived view with its authority.

## Reconsider when

Revisit if one tool can no longer express its assigned views, collaboration needs
change or diagram maintenance becomes disproportionate.
