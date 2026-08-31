# IGDB provider PoC

This disposable CLI evaluated IGDB against the frozen sample in
[`docs/research/igdb-poc-sample.csv`](../../docs/research/igdb-poc-sample.csv). The
authenticated run completed on 2026-07-24 with `CONDITIONAL_PASS`; the durable result
is [IGDB PoC results](../../docs/research/igdb-poc-results.md).

It is not a production adapter. It has no Spring, database or browser integration;
tests use local fixtures, and generated evidence stays under ignored `.poc/igdb/`.
A technical pass does not approve public or commercial use.

## Build

Requires JDK 21. Exact dependencies and plugins are defined by `pom.xml`.

```bash
./mvnw -f tools/igdb-poc/pom.xml clean verify
```

The executable is `tools/igdb-poc/target/igdb-poc.jar`. Local tests require neither
credentials nor IGDB network access.

## Authenticated capture

Create a confidential Twitch developer application and export
`IGDB_CLIENT_ID`/`IGDB_CLIENT_SECRET`. Avoid command-line secrets and shell history:

```bash
read -rp "IGDB Client ID: " IGDB_CLIENT_ID
read -srp "IGDB Client Secret: " IGDB_CLIENT_SECRET
echo
export IGDB_CLIENT_ID IGDB_CLIENT_SECRET
```

Verify authentication, then capture the bounded sample:

```bash
java -jar tools/igdb-poc/target/igdb-poc.jar smoke

java -jar tools/igdb-poc/target/igdb-poc.jar run \
  --sample docs/research/igdb-poc-sample.csv \
  --output .poc/igdb \
  --requests-per-second 3
```

The client is sequential and refuses a rate above three requests/second. It searches
titles, resolves identity conservatively, retrieves details/releases, normalizes into
provider-independent data and produces `actual-results.csv`, JSON and Markdown
reports. Ambiguous highest-scoring candidates remain unresolved.

## Offline validation

Repeat the deterministic comparison without contacting IGDB:

```bash
java -jar tools/igdb-poc/target/igdb-poc.jar validate \
  --sample docs/research/igdb-poc-sample.csv \
  --actual .poc/igdb/actual-results.csv \
  --output .poc/igdb-validation
```

Use a different output directory to preserve authenticated run telemetry. The tool
keeps raw captures, normalized cases and reports inside the ignored output directory;
do not move provider payloads or images into Git or Maven's disposable `target/`.

## Validation semantics

- Candidate selection normalizes controlled text variants, then checks type, parent,
  platform and release year; it never silently substitutes an edition or series item.
- Platform, region, date, precision and status must match in one release record.
- Day, month, year, quarter and unknown precision remain distinct; changed future
  dates require review rather than an automatic failure.
- Technical failures, normalization errors, discrepancies and manual reviews remain
  separate in the report.
- Threshold ownership belongs to the
  [provider spike](../../docs/research/game-data-providers-spike.md).

| Exit | Meaning |
|---:|---|
| `0` | `PASS` |
| `2` | `CONDITIONAL_PASS` or reviews remain |
| `3` | Acceptance threshold failed |
| `4` | Technical execution failed |
| `5` | Input or configuration is invalid |

Never commit credentials, access tokens, raw responses or provider images. Provider
terms, attribution, retention and release-mode restrictions remain binding even when
the CLI passes.
