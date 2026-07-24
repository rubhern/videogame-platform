# IGDB provider PoC

## Purpose and status

This module is a disposable command-line application for evaluating IGDB
against the frozen control sample and the acceptance thresholds recorded in the
provider spike.

It is deliberately isolated from any future product implementation:

- it is not a catalogue service or production adapter;
- it uses no Spring Boot, database, Docker image, browser integration, or
  distributed infrastructure;
- normal tests use local fixtures and never call Twitch or IGDB;
- generated evidence remains under the ignored `.poc/igdb/` directory, outside
  Maven's disposable `target/` build directory;
- a technical pass does not approve IGDB for public or commercial use.

The first authenticated PoC was executed and reviewed on 2026-07-24. Its durable
aggregate result is documented in
[`../../docs/research/igdb-poc-results.md`](../../docs/research/igdb-poc-results.md).
The generated decision was `CONDITIONAL_PASS`; raw and normalized evidence remains
local and ignored.

## Technology

- Java 21;
- Maven Wrapper for a reproducible build;
- Java `HttpClient` for OAuth and IGDB requests;
- Jackson for JSON;
- Apache Commons CSV for the control sample and offline evidence;
- Picocli for the `smoke`, `run`, and `validate` commands;
- JUnit 5 and AssertJ for fixture-based tests.

The client is sequential and refuses a configured limit above three requests
per second, leaving headroom below IGDB's documented limit.

## Data flow

```text
frozen sample
    -> title search
    -> strict candidate selection
    -> game details + release dates
    -> raw evidence
    -> provider-independent normalization
    -> actual-results.csv
    -> deterministic validation
    -> JSON and Markdown reports
```

Search, game details, and release dates are captured separately. The
`validate` command can therefore repeat the comparison from
`actual-results.csv` without contacting IGDB.

When a localized query cannot identify one reliable candidate, `run` performs
one canonical-title fallback search. Candidate selection then uses title, game
type, parent relationship, platform, and release year; tied highest-scoring
candidates remain unresolved instead of being selected arbitrarily.

## Prerequisites

- JDK 21;
- a confidential Twitch developer application;
- `IGDB_CLIENT_ID` and `IGDB_CLIENT_SECRET` available only as environment
  variables.

The Maven Wrapper downloads the pinned Maven distribution when necessary.

## Build and local tests

This command compiles the tool and runs only local tests:

```bash
./mvnw -f tools/igdb-poc/pom.xml clean verify
```

It must not require credentials or network access to IGDB. The executable JAR
is generated at:

```text
tools/igdb-poc/target/igdb-poc.jar
```

## Credential setup

Load credentials into the current shell without writing the secret in command
history:

```bash
read -rp "IGDB Client ID: " IGDB_CLIENT_ID
read -srp "IGDB Client Secret: " IGDB_CLIENT_SECRET
echo
export IGDB_CLIENT_ID IGDB_CLIENT_SECRET
```

The application obtains the app access token internally. It never accepts a
token as a CLI argument and never writes credentials or tokens to evidence.

## Commands

The following commands reproduce the authenticated and offline workflows. They are
manual and explicit because they require local credentials or generated evidence.

### Authentication smoke test

```bash
java -jar tools/igdb-poc/target/igdb-poc.jar smoke
```

This obtains a Twitch app token and performs one small IGDB query.

### Capture and validate the sample

```bash
java -jar tools/igdb-poc/target/igdb-poc.jar run \
  --sample docs/research/igdb-poc-sample.csv \
  --output .poc/igdb \
  --requests-per-second 3
```

This queries all control cases, preserves the raw and normalized evidence,
validates it, and produces aggregate reports.

### Repeat validation without network access

```bash
java -jar tools/igdb-poc/target/igdb-poc.jar validate \
  --sample docs/research/igdb-poc-sample.csv \
  --actual .poc/igdb/actual-results.csv \
  --output .poc/igdb-validation
```

Use a separate validation output when the authenticated run report must be preserved.
Pointing `validate --output` at the authenticated capture directory regenerates
`report.json` and `report.md` there and replaces run-only telemetry.

## Generated evidence

```text
.poc/igdb/
├── raw/
│   ├── <case>-search.json
│   ├── <case>-search-canonical.json     # only when fallback is required
│   ├── <case>-details.json
│   └── <case>-releases.json
├── normalized/
│   └── <case>.json
├── actual-results.csv
├── report.json
└── report.md
```

The entire generated directory is ignored by Git. After manual review, only
aggregated, permitted results should be transferred to a copy of
`docs/research/igdb-poc-results-template.md`.

Do not place captured evidence under `tools/igdb-poc/target/`: Maven treats that
directory as disposable and `mvn clean` deletes it.

## Validation rules

- Candidate selection permits controlled Unicode, punctuation, and leading
  article variants, then disambiguates with type, parent, platform, and release
  year. It must not silently select another edition, remaster, or series entry.
- Platform, region, date, precision, and status must match within one release
  record. Values from different releases are never combined.
- Full-day dates use IGDB's `date` epoch value, with `y/m/d` only as a fallback;
  known precision with an unparseable date is an explicit normalization error.
- `Worldwide` is compatible with a regional product query, while an expected
  `Unknown` means the sample makes no region assertion.
- Day, month, year, quarter, and unknown date precision remain distinct.
- A changed future date becomes `REVIEW`, because the frozen expectation may
  have become stale.
- HTTP failures, normalization errors, data discrepancies, manual reviews, and
  non-blocking limitations remain separate in the report.
- Multiple raw exact-title candidates are observational after a unique identity
  has been resolved; unresolved ties fail candidate selection.
- The final decision is derived from the confirmed thresholds in
  `docs/research/game-data-providers-spike.md`.

JSON arrays and release tuples are stored as JSON fields inside
`actual-results.csv`; this preserves enough structure for deterministic offline
validation.

## Exit codes

| Code | Meaning |
|---:|---|
| `0` | `PASS` |
| `2` | `CONDITIONAL PASS` or manual reviews remain |
| `3` | Acceptance threshold failure |
| `4` | Technical execution error |
| `5` | Invalid input or configuration |

## Security and decision boundaries

- Do not put secrets in `.env` files, command arguments, logs, fixtures, or
  committed reports.
- Do not call IGDB directly from a browser.
- Do not commit raw provider responses or images.
- Review provider terms for attribution, retention, images, public use, and
  future monetization before approving a production integration.
- Record a provider ADR only if the decision becomes durable for a public product
  architecture; the private learning PoC does not require one.
