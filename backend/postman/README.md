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
- [`session.postman_collection.json`](session.postman_collection.json): minimal
  anonymous session state and CSRF-protected logout rejection. The successful OIDC
  flow is intentionally covered by the real-browser identity gate instead of
  scripting credentials in Postman.
- [`local.postman_environment.json`](local.postman_environment.json): local
  `baseUrl`, defaulting to `http://localhost:8080`.

## Import and run

1. Start the backend from the repository root. The release collection expects a
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

2. In Postman, select **Import** and import the environment and all three collections.
3. Select the **VideoGame Platform - Local** environment.
4. Run **VideoGame Platform Backend - Catalogue Releases** and
   **VideoGame Platform Backend - Actuator**, then run **VideoGame Platform Backend -
   BFF Session** while signed out.

The product collection verifies the reviewed release-page shape, active and available
filters, correlation/cache/ETag headers, `304` weak-validator handling,
date/freshness states, and stable Problem Details codes. The operational collection
verifies HTTP `200`, discovery links, `UP`
for health and probes, generated build/source metadata, meter names, and bounded HTTP
route tags. Backend integration tests remain authoritative for PostgreSQL behaviour,
W3C propagation, structured correlation, and negative sensitive-data assertions.

To target another instance, change only the environment's `baseUrl`. Do not add
tokens, passwords, cookies, client secrets, or machine-specific values to these
tracked files. Create a private Postman environment for future authenticated APIs.

The collections document implemented behaviour; the reviewed
[`docs/architecture/api/openapi.yaml`](../../docs/architecture/api/openapi.yaml)
remains authoritative for the product contract. Whenever a backend API changes, its
tracked Postman requests and assertions must be updated in the same change.
