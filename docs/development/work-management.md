# Work management

- **Status:** Approved
- **Version:** 1.0
- **Owner:** Ruben Hernandez
- **Last updated:** 2026-08-06
- **Project:** [VideoGame Platform — Delivery](https://github.com/users/rubhern/projects/2)
- **Lifecycle:** [Learning MVP delivery lifecycle](delivery-lifecycle.md)
- **Scope:** Private, non-commercial learning MVP operated by one person

> This document records the minimum reproducible baseline for managing work with
> GitHub Issues and Projects. The delivery lifecycle remains authoritative for
> readiness, risk, review, acceptance, release, and the Definition of Done.

## 1. Sources of truth

- Repository documents and ADRs record durable product and technical decisions.
- GitHub Issues represent units of work.
- GitHub Projects manages priority and delivery status.
- Pull requests contain implementation and validation evidence.

The GitHub Project is the live operational view. This document records the intended
configuration and meaning so that it can be reviewed and reconstructed. When the live
configuration and this baseline differ, reconcile them explicitly instead of choosing
silently.

## 2. Workflow

`Inbox → Backlog → Ready → In progress → In review → In validation → Done`

| Status | Meaning | Exit condition |
|---|---|---|
| `Inbox` | New, unreviewed issue | Accept, reject, or request clarification |
| `Backlog` | Accepted work not yet prepared | Outcome, scope, dependencies, and risk are understood |
| `Ready` | Work can start without a blocking decision | Owner starts the item within the WIP limit |
| `In progress` | Implementation and local validation are active | A focused pull request is ready for review |
| `In review` | Pull request checks and review are active | The pull request is merged or returned for changes |
| `In validation` | Merge completed; applicable deployment, smoke tests, or acceptance remain | The change is accepted or returned for correction |
| `Done` | Applicable acceptance and Definition of Done conditions are satisfied | The issue is closed as completed |

Moving an item between intermediate states is intentionally manual. An item returned
from review or validation moves back to `In progress`. A reopened issue returns to
`Backlog` for re-evaluation unless immediate work has already started.

## 3. Project baseline

### Fields

All planning fields are single-select fields. Leave a field empty in `Inbox` only when
the information is genuinely unknown; populate it before moving the item to `Ready`.

| Field | Values | Use |
|---|---|---|
| `Status` | `Inbox`, `Backlog`, `Ready`, `In progress`, `In review`, `In validation`, `Done` | Delivery state defined above |
| `Priority` | `Now`, `Next`, `Later` | Ordering by current learning and delivery focus |
| `Slice` | `Walking skeleton`, `Releases`, `Game page`, `Authentication`, `Ratings`, `Mis puntuaciones`, `Platform and delivery`, `Future` | Approved journey activity or enabling delivery boundary |
| `Risk` | `Low`, `Medium`, `High`, `Emergency` | Highest applicable classification from the delivery lifecycle |
| `Size` | `XS`, `S`, `M`, `L` | Relative effort; an `L` item must be split before it becomes `Ready` |

`Now` is the current gate or immediate focus. `Next` is the next planned work after
the current WIP clears. `Later` is accepted work intentionally deferred beyond the
current focus. Use `Risk = Emergency`, rather than a priority value, for an active
incident that requires containment before normal planning.

### Views

| View | Layout | Purpose |
|---|---|---|
| `Delivery board` | Board grouped by `Status` and filtered to items with a status | Move work through the delivery lifecycle |
| `Backlog` | Table limited to `Inbox`, `Backlog`, and `Ready` | Triage and order upcoming work by priority, slice, risk, and size |
| `Current work` | Table limited to `In progress`, `In review`, and `In validation` | Keep the WIP limit and pending acceptance visible |

The views are convenience projections. Fields and status meaning remain authoritative
when a view is renamed, filtered, or temporarily rearranged.

### Automations

Configure the Project's built-in workflows as follows:

| Workflow | Configuration | State |
|---|---|---|
| Auto-add to project | Repository `rubhern/videogame-platform`; filter `is:issue` | Enabled |
| Auto-add sub-issues to project | No separate rule until sub-issues become a deliberate planning mechanism | Disabled |
| Item added to project | Set `Status` to `Inbox` | Enabled |
| Item closed | Set `Status` to `Done` | Enabled |
| Pull request linked to issue | Intermediate state changes remain manual | Disabled |
| Pull request merged | Move the referenced issue manually to `In validation` | Disabled |
| Auto-close issue | Do not close the issue when `Status` changes to `Done`; close it explicitly after acceptance | Disabled |
| Auto-archive | May archive completed issues after 30 days when the board needs pruning | Optional |

Existing issues must be added manually once; auto-add only handles issues created or
updated after the workflow is enabled. Pull requests are not Project items because the
linked issue is the unit of work and the pull request is its implementation evidence.

## 4. Labels

Labels classify work across repository searches and issue forms. Project fields own
priority, slice, risk, size, and delivery state; do not duplicate them as labels.

| Work type | Use |
|---|---|
| `type:bug` | Reproducible incorrect behaviour |
| `type:feature` | User-facing product capability |
| `type:enabler` | Technical or operational work enabling delivery |
| `type:spike` | Bounded investigation that resolves uncertainty |
| `type:documentation` | Documentation-only work |

| Affected area | Use |
|---|---|
| `area:product` | Product scope, evidence, or research |
| `area:architecture` | Architecture models, decisions, or boundaries |
| `area:api` | HTTP API contract or behaviour |
| `area:backend` | Backend application or domain implementation |
| `area:frontend` | Web frontend or user interface |
| `area:platform` | Delivery, infrastructure, operations, or observability |
| `area:provider` | External game-data provider integration |

Bug and spike forms apply their type automatically. The generic work-item form records
`Feature`, `Enabler`, or `Documentation`; assign the corresponding type label during
triage. Assign all applicable area labels during triage, but avoid labels that do not
support a useful filter or decision.

## 5. Working rules

- Keep at most two issues in `In progress`; prefer one active item when possible.
- Use `Related to #<issue-number>` in a pull request while post-merge validation or
  acceptance remains.
- Use `Closes #<issue-number>` only when merging the pull request satisfies every
  applicable acceptance step for that issue.
- For material changes, keep the issue open through `In validation`; close it only
  after applicable `dev` deployment, smoke tests, and acceptance succeed.
- A separate issue is optional for a very small change when the pull request provides
  sufficient context and traceability.
- Close rejected work as `not planned`; do not move it to `Done` as completed work.

## 6. Change history

| Version | Date | Change | Owner |
|---|---|---|---|
| 1.0 | 2026-08-06 | Established the GitHub Project baseline, issue lifecycle, labels, automations, views, and acceptance-aware closure rules. | Ruben Hernandez |
