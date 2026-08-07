# Actuator Postman assets

This directory contains a Postman collection for every Actuator operation currently
exposed by the backend and a non-secret local environment.

## Files

- [`actuator.postman_collection.json`](actuator.postman_collection.json): discovery,
  aggregate health, liveness, readiness, and info requests with automated tests.
- [`local.postman_environment.json`](local.postman_environment.json): local
  `baseUrl`, defaulting to `http://localhost:8080`.

## Import and run

1. Start the backend from the repository root:

   ```bash
   ./mvnw -pl backend spring-boot:run
   ```

2. In Postman, select **Import** and import both JSON files.
3. Select the **VideoGame Platform - Local** environment.
4. Open **VideoGame Platform Backend - Actuator** and select **Run collection**.

The collection verifies HTTP `200`, JSON responses, discovery links, and `UP` for
the aggregate health and probe endpoints. The info request accepts the current empty
JSON object so that future non-sensitive metadata can be added without changing the
request.

To target another instance, change only the environment's `baseUrl`. Do not add
tokens, passwords, cookies, client secrets, or machine-specific values to these
tracked files. Create a private Postman environment for future authenticated APIs.

The collection documents the implemented operational surface, not the approved
product OpenAPI contract. Product endpoints must remain described by
[`docs/architecture/api/openapi.yaml`](../../docs/architecture/api/openapi.yaml) and
will require their own collection when implemented.
