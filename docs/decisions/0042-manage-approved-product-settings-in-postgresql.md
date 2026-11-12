# ADR-0042: Manage approved product settings in PostgreSQL

- **Status:** Proposed
- **Date:** 2026-11-12
- **Owner:** Ruben Hernandez
- **Scope:** Operator-editable presentation, ranking and acquisition-window values; the
  classification of every other configuration
- **Issue:** [#450](https://github.com/rubhern/videogame-platform/issues/450)
- **Related:** [ADR-0021](0021-rank-monthly-featured-releases-by-local-igdb-hypes.md),
  [ADR-0028](0028-authorize-catalogue-operators-with-a-bff-client-role.md),
  [ADR-0034](0034-rank-topometro-from-catalogue-score-projections.md),
  [ADR-0041](0041-rank-release-attention-by-igdb-signals-and-an-editorial-list.md)

## Context

Several product values were constants in code or deployment variables: the featured navigation
bounds, the Próximos destacados horizon, the release-attention weights, the TOPómetro minimum
samples, the highlights-calendar threshold, its interest bands and its day preview size, and the
days each scheduled synchronization requests. Changing any of them needed a release, and the
interest bands lived only in the frontend. The owner asked to change them from the
administration panel, effective without a restart or a redeployment (#450).

Moving every environment variable into the database was rejected by the issue itself: secrets,
endpoints, credentials, telemetry and safety bounds would widen the attack surface and
complicate recovery.

## Decision

- **A closed, typed registry.** `ProductSetting` in the catalogue application lists every
  editable setting with a stable key, group, kind (integer or decimal of at most four places),
  inclusive range and approved default. Nothing outside it is editable; there is no generic
  key/value editor. A new setting needs an explicit owner decision, a registry entry and the
  matching contract key. The registry is the executable owner of keys, ranges and defaults; this
  record does not restate them.
- **Only overrides are stored.** Flyway `V20261112_130000` creates
  `catalogue.product_setting` (one row per operator override),
  `catalogue.product_setting_change` (the audit: revision, key, previous and new effective
  values, whether the default was restored, time and the operator's derived `UserId`) and a
  single revision counter. A setting without a row takes its default, so neither a restart nor
  a deployment ever overwrites an operator's choice, and removing a row is the recovery for any
  value.
- **Defaults of the synchronization windows come from the deployment.** The four
  `synchronization.*` settings default to the configured scheduling policies, so the database
  only adds a difference an operator chose. Their cron expressions stay deployment
  configuration: the production installer reviews schedules (#484), and a changed window applies
  from the next scheduled run. A configured window outside its setting's range refuses to start.
- **Read once per operation, from PostgreSQL.** Every affected read takes one snapshot of the
  effective settings and uses it throughout, so a change applies from the next request in every
  instance, with no cache and no process-local source of truth. A stored value that no longer
  fits its setting, or a stored combination that breaks a cross-setting rule, is ignored in
  favour of the defaults of that rule's settings and reported as `invalid_override`; a read never
  fails because of it.
- **Validated, all-or-nothing updates guarded by a revision.** An update names the revision it
  was prepared from and applies every change or none: unknown or repeated keys, values outside
  their kind or range, all three provider attention weights at zero, or calendar interest bands
  that do not strictly ascend are refused. The revision row is locked, so of two updates
  prepared from one revision exactly one wins and the other is a `409`.
- **Operator-only API.** `GET` and `PUT /api/v1/admin/catalogue/product-settings` (`UC-024`) sit
  behind the existing operator authority, session CSRF and same-origin checks. The operator in
  the audit is the session's server-derived identity, never a client claim.
- **Presentation does not imply acquisition.** Featured months forward are no longer clipped to
  the scheduled acquisition horizon (owner decision, 2026-11-12, superseding ADR-0021's
  bounded-navigation amendment); the setting decides, and the panel warns when Destacados, the
  calendar or Próximos destacados reach beyond the configured horizon, recommending a wider
  weekly window or a requested synchronization. Nothing requests provider work when a setting
  changes.
- **Public validators follow the settings.** Every affected public response is a function of the
  snapshot it read, and its strong ETag hashes the representation, so a change never revalidates
  an old representation. The calendar now carries its interest bands and day preview size in the
  response instead of frontend constants.

## Configuration classification

| Class | What | Where it stays |
|---|---|---|
| Editable product policy | Featured weeks and months back and forward; Próximos destacados horizon; release-attention weights and shares; TOPómetro minimum ratings per source; calendar minimum Hypes, day preview size and four interest bands | `ProductSetting` registry, PostgreSQL overrides, administration panel |
| Editable operational window (owner decision) | Days back and forward of the daily (`near-term`) and weekly (`upcoming`) scheduled synchronizations | Registry and overrides; defaults in `application.yaml` |
| Operational policy needing a separate safety decision | Synchronization cron expressions and the scheduling switch; IGDB page size, rate and retries; synchronization retention and abandonment; release freshness threshold; catalogue translation execution | `application.yaml` and the deployment's reviewed environment; candidates only through a new decision |
| Runtime, deployment, security setting or secret | Database URLs and credentials, OIDC issuer and endpoints, session lifetime and cookies, SMTP and feedback mailboxes, IGDB credentials and endpoints, GA4 measurement ID, telemetry endpoints and switches, management address, admission limits, cache-control headers | `application.yaml`, environment files and the [platform and delivery design](../architecture/deployment/mvp-platform-and-delivery.md); never in the database |
| Invariant or build-time limit | Release group and search context limits, page sizes, the featured limit of six and the Próximos destacados limit of ten (tied to the page layouts), attention scale and resolution, launch stages, editorial file limits | Code and contract; changed only by a release |

## Alternatives considered

- **Every environment variable in the database.** Rejected by #450: secrets and endpoints in a
  browser editor and a database row widen the attack surface and break recovery.
- **A cached or event-driven configuration service.** No measured need: one primary-key read of
  at most a few dozen rows per affected request is cheaper than invalidation across instances.
- **Per-setting endpoints with per-setting versions.** Cross-setting rules (weights, ascending
  bands) need one consistent view; a single revision keeps concurrency and audit simple.
- **Keep featured months bounded by acquisition.** Rejected by the owner: raising the setting
  would have done nothing without also widening acquisition. A warning keeps the risk visible.
- **Editable cron expressions.** Rejected by the owner; they stay reviewed deployment input.

## Consequences

- An operator can make Destacados, the calendar or Próximos destacados show periods the
  catalogue never acquired. The panel says so; coverage remains ADR-0017's evidence.
- Weights tuned within `attention-v1` keep its version: the version names the shape of the
  formula, not its weights. A new signal or scale is still a new version.
- The audit holds the operator's derived `UserId`, recorded in the
  [data-protection record](../development/data-protection.md).
- Rollback is a code revert: the tables are additive and unread by earlier versions, and the
  defaults reproduce the previous behaviour except that featured months forward default to five
  instead of following the acquisition horizon.

## Reconsider when

- An operator needs a setting outside the registry, or a scheduled time edited at runtime.
- The audit needs retention, export or erasure beyond the operator's own account.
- Reading the settings shows up in a measured request budget.
