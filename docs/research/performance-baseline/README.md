# Performance baseline

## Objective and evidence boundary

This research for [#85](https://github.com/rubhern/videogame-platform/issues/85)
measures the existing local catalogue, supplies deterministic disposable datasets,
and records the [first minimal public HTTP baseline](http-baseline.md). That low-rate
run validates the measurement path. It establishes no saturation, recovery or
production-capacity result. Stress, authenticated traffic, synchronization
interference and production optimizations remain outside the implemented slices.

`growth` and especially `large` are **synthetic performance/capacity profiles**.
Their cardinalities and distributions are experimental inputs, **not product
requirements, forecasts, or infrastructure commitments**. Product direction remains
in the [Product Brief](../../product/product-brief.md).

## Environment

The local measurement was taken on 2026-10-07 in Europe/Madrid, from the supported
local PostgreSQL container and `videogame_platform` database, using the application
role in a repeatable-read, read-only transaction. All 36,090 release snapshots have
external-provider provenance: this is the locally synchronized catalogue, not the
old demonstration seed. Its last recorded release synchronization was 2026-10-05
05:11 UTC. The local container has a 512 MiB memory limit and 0.5 CPU.

The synthetic harness uses the same local Docker engine, a separate PostgreSQL
container, a loopback-only port and an anonymous disposable volume, with limits of
1 GiB and 2 CPUs. The Docker Desktop engine reported 32 CPUs and about 15.2 GiB
of available VM memory on x86_64. Executable configuration belongs to
[dataset.sh](../../../tools/performance/dataset.sh). Generation timings and relation
sizes describe fixture construction; they do not measure application serving.

## Dataset profiles

| Profile | Games | Releases | Ratings | Meaning |
|---|---:|---:|---:|---|
| `current` | 20,241 | 36,090 | 7 | Measured local state; recalculate when synchronized data changes |
| `growth` | 50,000 | 200,000 | 500,000 | Synthetic intermediate capacity experiment |
| `large` | 200,000 | 2,000,000 | 5,000,000 | Synthetic high-cardinality capacity experiment |

Games and Releases count durable identities; their visible snapshots have identical
counts in this measurement. Ratings count active `(user, game)` rows, not historical
rating events. There is one current revision, 73 platforms, 11 regions, 12,217 Games
with genres and no approved aliases. Seven Games have Ratings, all belonging to
one anonymized owner. No owner identity is captured.

The frozen aggregate [current-profile.json](current-profile.json) retains the
timestamp, full measurement and storage totals. Useful generation inputs include:

| Distribution | Measured `current` |
|---|---|
| Releases per Game | 12,773 with 1; 6,782 with 2–5; 624 with 6–10; 62 with 11–25 |
| Date precision | 30,666 day; 440 month; 1,752 quarter; 2,740 year; 492 unknown |
| Persisted release evidence | 36,046 announced; 44 cancelled |
| Region concentration | 34,601 worldwide, about 96% of Releases |
| Platform concentration | 19,022 Windows PC, about 53% of Releases |
| Ratings per owner | One owner with 7 active Ratings |
| Date coverage | 1981-01-01 through 2029-03-31 |

Synthetic distributions approximate the dominant worldwide region, PC concentration
and predominantly exact-day dates. They deliberately increase fan-out and add
selective/searchable cases. They are not a statistical replica of the full historical
catalogue; the sparse current Ratings cannot justify a real user-history forecast.

## Reproduction

Run from the repository root with the supported local Docker engine available.
The profiler selects only PostgreSQL in the fixed local Compose project, never a
private-dev host. If stopped, start it through the
[supported local procedure](../../development/local-setup.md#start-verify-and-stop).

```bash
bash tools/performance/dataset.sh current > /tmp/current-profile.json
bash tools/performance/dataset.sh generate growth 2026-10-07 > /tmp/growth-profile.json
bash tools/performance/dataset.sh generate large 2026-10-07 > /tmp/large-profile.json
bash tools/performance/dataset.sh inspect
bash tools/performance/dataset.sh stop
```

`current` only measures. When PostgreSQL is unavailable, leave its values unmeasured
and rerun this command once available. The standalone
[profile.sql](../../../tools/performance/profile.sql) can also be passed to `psql`
on the supported local database; its SQL enforces read-only execution internally.

Generation atomically replaces only the dedicated synthetic dataset. IDs, titles,
aliases, dates, scores and version tokens are deterministic for the profile and
reference date, without randomness or provider calls. For later date-sensitive API
experiments, regenerate using the application's Europe/Madrid evaluation date and
record that date with the result. Physical layout, storage size and planner
statistics are not promised to be byte-identical between runs.

The wrapper replays the existing immutable
[migration SQL](../../../backend/src/main/resources/db/migration) in version order,
without applying or changing development seed SQL. This fixture has no Flyway
history. A backend used later against it must disable Flyway and provider/scheduling
work; normal application migration procedures are unchanged. The schema checksum
is recorded: after migration changes, `stop` and regenerate.

For a later host-run backend, connect to
`jdbc:postgresql://127.0.0.1:55432/videogame_performance`, using the fixture-only
`videogame_app` role/password `performance-local-only` and
`APPLICATION_FLYWAY_ENABLED=false`. These credentials are unrelated to local or
private-dev secrets. This slice starts no application and provisions no accounts.

## Generation and interpretation decisions

[generate.sql](../../../tools/performance/generate.sql) uses `generate_series()`
and ordinary inserts, with the existing indexes, generated search/period columns,
foreign keys, CHECK constraints and unique constraints enabled. Offline work grows
with the selected profile in PostgreSQL; application/client memory does not hold
the catalogue. Release statements process 10,000 Games, at most 100,000 Releases;
rating statements process one owner, at most 10,000 Ratings. `ANALYZE` prepares
planner statistics for subsequent experiments.

- One singleton current revision and one visible snapshot per identity preserve
  catalogue reads. Release tuple uniqueness, including null date fields, is retained.
  Fan-out is 1/2/3/10 Releases per Game for `growth`, and 1/3/11/25 for `large`,
  each assigned to a quarter of Games. The maximum respects the acquisition bound.
  Ten platform codes and five regions simplify the real 73/11 taxonomy. All Games
  have Releases; zero-release Games and decades of historical date coverage are
  not represented.
- Every title contains `Adventure`; every thousandth contains `Needle`; every
  tenth contains accented `Élite`. Unique numbered suffixes support individual hits.
  One fifth of Games have an approved `Aliasquest` alias. These intentional cases
  exercise broad, selective, prefix, multi-token and normalized-text matching,
  without forecasting real title/alias frequencies.
- All five date precisions coexist. Other release slots span roughly two years
  either side of the reference date; rating-eligible Games have an additional exact
  past release outside that band. One fifth of Games have only future/unknown dates
  and receive no Ratings. Known dates persist `announced`; runtime derives their
  lifecycle from dates. Delayed/cancelled evidence, review flags and different
  stages provide branch coverage; negative/review evidence is deliberately more
  common than in `current`.
- `growth` has 4,000 owners with 100 Ratings and 20 with 5,000; `large` has 16,000
  with 250 and 100 with 10,000. Owner/game pairs are unique, scores remain 1–10,
  timestamps are ordered and version tokens are deterministic. A shared pool of
  1,000 eligible Games attracts about one sixth of Ratings; the remainder covers
  the eligible long tail. Repeated scores/update dates create sorting ties.
  Synthetic owner UUIDs are not authenticated accounts: later load tests need an
  explicit identity mapping while preserving ownership and CSRF checks.
- Ratings require an occurred, full-release, non-cancelled, non-delayed,
  not-required release. The Ratings-owned listing, aliases and genre projection
  are coherent. Each Game has two genres, one mode, developer/publisher credits
  and a bounded editorial summary. Featured evidence is present and covers use
  the product-owned fallback. Provider CDN media, translations, real provider
  references, synchronization runs and backfill behaviour are not simulated.

For later pagination experiments, `Adventure` has 50,000/200,000 hits, `Needle`
50/200, and `Aliasquest` 10,000/40,000. At page size 20, `Adventure` provides
2,500/10,000 pages; deep rating histories provide 250/500. These are data-selection
cases, not implemented load scenarios or latency targets. Production queries and
their ordering rules are unchanged.

## Disposable-data boundary

The wrapper accepts a profile/date, never a database URL, container/volume name or
remote engine. Before generation/removal it checks the dedicated container label,
image, automatic removal and sole anonymous volume. Bind mounts and persistent
named volumes are rejected. SQL independently requires the dedicated database
name, disposable marker and matching schema checksum before truncating only the
Catalogue/Ratings fixture tables; no `CASCADE` reaches other schemas. A transaction
and advisory lock serialize replacement. Failed generation rolls back; `stop`
removes only the verified disposable container and its anonymous volume.

These controls prevent supported commands from targeting persistent local or
private-dev data. Do not manually transplant the marker or rewrite the guards.

## Evidence and remaining work

Focused validation comprises shell syntax, boundary regression checks, PostgreSQL
generation with all constraints enabled, generator semantic/count assertions,
deterministic reruns and documentation-link validation. Run the fast safety checks
with `bash tools/performance/test-safety.sh`.

Both profiles were generated and checked on 2026-10-07 with reference date
2026-10-07. The aggregate outputs are frozen in
[growth-profile.json](growth-profile.json) and [large-profile.json](large-profile.json).

| Profile | Generation and verification | Catalogue/Ratings tables + indexes | Whole database |
|---|---:|---:|---:|
| `current` | Read-only inventory | 105.81 MiB | 115.17 MiB |
| `growth` | 25–26 seconds warm; 45 seconds after fresh setup | 486.83 MiB | 495.70 MiB |
| `large` | 409 seconds, about 6 minutes 49 seconds | 3,828.72 MiB, about 3.74 GiB | 3,837.79 MiB |

Generation includes semantic/count verification and `ANALYZE`, and excludes initial
container/schema setup and the final inventory query. Sizes exclude WAL, temporary
files, Docker/image overhead and peak disk requirements during build/replacement;
they are not estimates of the total free disk space needed. The large observation
is one run, and warm caches/storage make timings environment-dependent.

Validation passed:

- Shell syntax and 14 fake-Docker safety cases, including remote context precedence,
  unrelated containers, persistent/bind-mounted volumes and invalid input.
- Both full synthetic profiles with existing PostgreSQL CHECK/FK/UNIQUE/generated
  column rules enabled, plus cardinality, projection-presence, release-bound and
  rating-eligibility assertions. Selective and accent-normalized title cohorts
  include rated Games.
- Final `growth` replacement/rerun: row counts and order-independent full-row
  fingerprints matched across all 32 Catalogue/Ratings tables. Physical storage
  is intentionally excluded from the determinism promise. `large` was executed
  once; its profile/date use the same deterministic construction.
- Broad/rare/accent title counts of 50,000/50/5,000 and 200,000/200/20,000, and
  alias counts of 10,000/40,000. Catalogue offsets 10,000/100,000 and rating offsets
  4,000/9,000 each returned 20 rows using the application database role.
- Direct SQL rejection of the normal application database name and an incorrect
  schema checksum. Sentinel data remained intact. Standalone profiling succeeded;
  the supported local catalogue's counts/distributions remained unchanged.
- Documentation validation and diff whitespace checks. The disposable container
  and exact verified anonymous volume were removed after measurement, including
  a fresh-setup/cleanup check. Volume removal uses no force and refuses a volume
  in use elsewhere. No persistent database was reset.

The first public HTTP run, existing server telemetry correlation and their limits
are recorded in [HTTP baseline evidence](http-baseline.md). Broader workloads,
success/saturation criteria, complete host measurement, background-work interference
and recovery remain pending in #85. No production optimization,
architecture change, normal migration or development seed change was made. This
harness is outside releasable backend/frontend/OpenAPI artefacts, so this slice has
no version increment. Broad application suites were not run for this tooling-only
slice; publication/CI and owner acceptance remain separate steps.
