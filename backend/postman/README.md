# Backend Postman assets

This directory contains executable Postman examples for the implemented product and
Actuator APIs plus a non-secret local environment.

## Files

- [`actuator.postman_collection.json`](actuator.postman_collection.json): discovery,
  aggregate health, liveness, readiness, build info, and metric requests with
  automated tests.
- [`catalogue-releases.postman_collection.json`](catalogue-releases.postman_collection.json):
  recent/upcoming release discovery, product filters, public headers, weak-validator
  conditional reads, `int64` page totals, strict query parameters, pagination, and
  stable validation errors with automated tests.
- [`catalogue-search.postman_collection.json`](catalogue-search.postman_collection.json):
  bounded catalogue search by canonical title and approved alias, diacritic-insensitive
  and partial matching, ambiguous and zero-result outcomes, deterministic pagination,
  public headers, weak-validator conditional reads, and stable validation errors with
  automated tests.
- [`session.postman_collection.json`](session.postman_collection.json): minimal
  anonymous session state and CSRF-protected logout rejection. The successful OIDC
  flow is intentionally covered by the real-browser identity gate instead of
  scripting credentials in Postman.
- [`local.postman_environment.json`](local.postman_environment.json): local product
  `baseUrl` (`http://localhost:8080`) and loopback-only `managementBaseUrl`
  (`http://localhost:8081`).

## Import and run

1. Start the backend from the repository root. The catalogue collections expect a
   fresh disposable local database initialized with the deterministic seed:

   ```bash
   bash scripts/local-dependencies.sh up
   set -a
   source backend/.env
   set +a
   APPLICATION_FLYWAY_ENABLED=true \
   SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/dev-seed \
   ./mvnw -pl backend spring-boot:run
   ```

2. In Postman, select **Import** and import the environment and all four collections.
3. Select the **VideoGame Platform - Local** environment.
4. Run **VideoGame Platform Backend - Catalogue Releases**, **VideoGame Platform
   Backend - Catalogue Search** and **VideoGame Platform Backend - Actuator**, then run
   **VideoGame Platform Backend - BFF Session** while signed out.

The release collection verifies the reviewed release-page shape, active and available
filters, correlation/cache/ETag headers, `304` weak-validator handling,
date/freshness states, stable Problem Details codes, and equality between each error
body correlation ID and its response header. The search collection verifies the
game-search-page shape, canonical-title and approved-alias matching, the reported match
context, ambiguous and zero-result outcomes, deterministic pagination, the absence of
any provider identifier, and the same header and Problem Details rules. The operational
collection verifies HTTP `200`, discovery links, `UP`
for health and probes, generated build/source metadata, meter names, and bounded HTTP
route tags. Backend integration tests remain authoritative for PostgreSQL behaviour,
W3C propagation, structured correlation, and negative sensitive-data assertions.

To target another instance, change the environment's product and management base
URLs while keeping the management address on its approved private boundary. Do not
add tokens, passwords, cookies, client secrets, or machine-specific values to these
tracked files. Create a private Postman environment for future authenticated APIs.

The collections document implemented behaviour; the reviewed
[`docs/architecture/api/openapi.yaml`](../../docs/architecture/api/openapi.yaml)
remains authoritative for the product contract. Whenever a backend API changes, its
tracked Postman requests and assertions must be updated in the same change.
