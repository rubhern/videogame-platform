---
name: product-brief-review
description: Review or update VideoGame Platform product alignment through the Product Brief, source vision, assumptions, open questions, glossary, and optionally the story map. Use for Product Brief completeness, coherence, evidence, scope, hypotheses, risks, or approval readiness. Do not use for backend implementation, technical refactoring, Java/Spring review, persistence design, or code architecture unless the task explicitly asks to review their product reasoning.
---

# Product Brief Review

Review the product documentation as an alignment system, not as isolated files.
Preserve uncertainty and make the next decision easier.

## Read order

1. Read `docs/reference/README.md` and the source PDF when fidelity to the
   original vision matters.
2. Read `docs/product/product-brief.md`.
3. Read `docs/product/assumptions.md`, `open-questions.md`, and `glossary.md`.
4. Read `docs/product/mvp-story-map.md` only when journey, release-cut, or scope
   alignment is part of the review.
5. Read `AGENTS.md` for current phase constraints.

## Review workflow

1. Identify the requested review scope and document versions.
2. Classify material statements as evidence, decision, assumption, proposal, or
   unresolved question.
3. Check that the priority user, problem, value proposition, primary journey,
   MVP boundary, success signals, and risks form a coherent chain.
4. Detect claims not supported by the source vision, research, or an explicit
   decision.
5. Check that long-term capabilities have not silently entered MVP scope.
6. Check that data licensing, differentiation, and provider dependency remain
   visible risks.
7. Update assumptions and open questions when the review changes their status.
8. Report approval readiness and the smallest next actions.

## Guardrails

- Do not convert a working hypothesis into an approved decision.
- Do not invent research evidence, owners, dates, budgets, targets, or provider
  permissions.
- Do not introduce architecture or implementation decisions to fill product
  gaps.
- Keep the Product Brief concise; put detailed evidence in `docs/research/`.
- Preserve resolved questions for traceability.
- Change document status to `Approved` only after an explicit owner decision.

## Output

Lead with the readiness conclusion. Group findings as:

- `Important`: can invalidate or materially redirect the product.
- `Minor`: reduces clarity, traceability, or consistency.
- `Suggestion`: useful improvement that does not block the current phase.

For edits, keep the diff focused and run:

```bash
bash scripts/validate-docs.sh
```
