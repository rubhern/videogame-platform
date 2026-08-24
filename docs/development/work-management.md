# Work management

- **Status:** Approved
- **Owner:** Ruben Hernandez
- **Project:** [VideoGame Platform — Delivery](https://github.com/users/rubhern/projects/2)

Repository records and ADRs own durable decisions; GitHub Issues own work items;
GitHub Projects owns priority/status; pull requests own implementation and validation
evidence. Reconcile any difference with the live Project explicitly.

## Workflow

`Inbox → Backlog → Ready → In progress → In review → In validation → Done`

| Status | Meaning |
|---|---|
| `Inbox` | Unreviewed item |
| `Backlog` | Accepted but not prepared |
| `Ready` | Outcome, scope, dependencies, risk, size, and acceptance criteria are sufficient to start |
| `In progress` | Implementation/local validation active |
| `In review` | Pull request checks and review active |
| `In validation` | Merge complete; deployment, smoke, or acceptance remains |
| `Done` | Applicable acceptance and Definition of Done complete |

Return failed review/validation to `In progress`. A reopened issue returns to
`Backlog` unless work has already resumed. Keep at most two items in progress and
split any `L` item before `Ready`.

## Project fields

| Field | Values |
|---|---|
| `Priority` | `Now`, `Next`, `Later` |
| `Slice` | `Walking skeleton`, `Releases`, `Game page`, `Authentication`, `Ratings`, `Mis puntuaciones`, `Platform and delivery`, `Future` |
| `Risk` | `Low`, `Medium`, `High`, `Emergency` |
| `Size` | `XS`, `S`, `M`, `L` |

Use labels only for work type (`bug`, `feature`, `enabler`, `spike`,
`documentation`) and affected area (`product`, `architecture`, `api`, `backend`,
`frontend`, `platform`, `provider`). Do not duplicate Project fields as labels.

Use `Related to #123` while post-merge validation remains. Use `Closes #123` only
when merge completes every applicable acceptance step. Close rejected work as
`not planned`; do not report it as completed. When work starts or changes phase,
update the Project status so the live board remains authoritative.
