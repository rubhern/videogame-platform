# OpenAPI workflow

The browser-facing contract is
[`docs/architecture/api/openapi.yaml`](../architecture/api/openapi.yaml). It is the
only maintained definition of paths, operations, parameters, schemas, examples,
headers, security requirements, and error responses. The generated
[`reference/index.html`](../architecture/api/reference/index.html), backend Java, and
frontend TypeScript are derived artefacts.

## Change the contract

1. Start from an approved use case and update `openapi.yaml` before implementation.
2. Assess the contract version using the
   [delivery lifecycle](delivery-lifecycle.md).
3. Run:

   ```bash
   npm ci
   bash scripts/build-openapi-docs.sh
   npm run frontend:generate-api
   ./mvnw -pl backend clean compile
   ```

4. Inspect the contract diff, generated frontend types, generated Spring interfaces,
   and rendered HTML.
5. Update the relevant tracked Postman collection and behavioural tests.
6. Run the smallest additional backend or frontend validation justified by the
   affected operations.

`build-openapi-docs.sh` validates syntax, project rules, references, schemas, and
examples before regenerating the committed HTML. Do not edit the HTML directly.

## Backend generation boundary

The Maven execution in [`backend/pom.xml`](../../backend/pom.xml) generates Spring
interfaces and transport models below
`backend/target/generated-sources/openapi`. Generated Java is disposable and must
never be edited or committed.

Manual controllers in `com.videogameplatform.api.delivery` implement the generated
interfaces and map to provider-independent application models. Generated types must
not enter application, domain, persistence, identity, ratings, catalogue, or
provider code; architecture tests enforce this direction.

The generator configuration and exact version live in the Maven manifests. Any
compatibility workaround belongs beside that configuration or in a focused comment
in the OpenAPI source, not in a second manual. A generator upgrade must regenerate
the complete contract, compile representative polymorphic/null models, and pass the
affected HTTP tests.

## Frontend generation boundary

`openapi-typescript` writes
`frontend/src/shared/api/generated/schema.d.ts`. Product-facing API functions hide
the generated transport surface from components. Never hand-edit generated types or
create parallel DTOs to bypass the contract.

## Validation ownership

- `package.json` owns executable commands and exact Node tool versions.
- `redocly.yaml` and `tools/openapi-validation/` own validation rules.
- `backend/pom.xml` owns Spring generation options.
- `frontend/package.json` owns TypeScript generation.
- OpenAPI owns wire behaviour; [API conventions](../architecture/api/api-conventions.md)
  own cross-operation policies not expressible clearly in the contract.

Fix failures in the owning source. Never patch generated output or suppress a
contract error without an explicit reason.
