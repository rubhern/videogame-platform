---
name: openapi-change
description: Change a VideoGame Platform product-facing HTTP API contract-first, in the required order: OpenAPI, regeneration, backend delivery, frontend consumer, Postman, tests. Use whenever an endpoint, parameter, schema, header, status code, or error response is added or modified. Do not use for internal refactoring that leaves the wire contract unchanged.
---

# OpenAPI change

Every product-facing backend HTTP API is contract-first from
`docs/architecture/api/openapi.yaml`. Implementation never leads the contract.

`docs/development/openapi.md` owns the workflow detail and generation boundaries, and
`docs/architecture/api/api-conventions.md` owns cross-operation HTTP policy. Read both
before the first edit; this skill only enforces the order and the stop conditions.

## Required order

1. **Approve the need.** Identify the use case in
   `docs/architecture/application/mvp-use-cases.md`. An endpoint without an approved
   use case is out of scope; stop and report.
2. **Edit the contract.** Change `docs/architecture/api/openapi.yaml` first: paths,
   parameters, schemas, examples, headers, security, and every error response.
3. **Assess the version.** Apply the Semantic Versioning policy in
   `docs/development/delivery-lifecycle.md` to the contract artefact. An intentional
   incompatibility needs an explicit compatibility decision and migration notes.
4. **Validate and regenerate**, in this order:

   ```bash
   npm ci
   bash scripts/validate-openapi.sh
   bash scripts/build-openapi-docs.sh
   npm run frontend:generate-api
   ./mvnw -pl backend clean compile
   ```

5. **Implement the backend.** Manual controllers in `com.videogameplatform.api.delivery`
   implement the generated interfaces and own all mapping to provider-independent
   application models.
6. **Implement the frontend.** Consume the generated types through the product-facing
   API functions; never hand-write a parallel DTO.
7. **Update the tracked Postman collection**, its requests, and its assertions in the
   same change.
8. **Update behavioural tests** for the new or changed operations.
9. **Update affected documentation** and run the checks the `validate` skill selects.

## Hard stops

- Never edit or commit generated Java under `backend/target/generated-sources`.
- Never hand-edit `frontend/src/shared/api/generated/schema.d.ts`.
- Never edit `docs/architecture/api/reference/index.html`; it is regenerated.
- Generated OpenAPI types must not reach application, domain, catalogue, ratings,
  persistence, identity, or provider code. `HexagonalArchitectureTest` enforces this.
- Fix a validation failure in the owning source. Never suppress a contract error or
  patch generated output to make a gate pass.
- Do not add an operation, field, or status code that no approved use case requires.

## Review before reporting done

- The contract diff, the generated frontend types, the generated Spring interfaces,
  and the rendered HTML were all inspected.
- Error responses follow the API conventions rather than a new ad-hoc shape.
- Pagination has deterministic total ordering with a uniquely identifying final
  tie-breaker, and the query work stays bounded; load `scalability-by-design` for any
  collection, filter, or search operation.
- Postman, tests, and documentation changed atomically with the contract.
