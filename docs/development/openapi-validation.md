# OpenAPI contract validation

- **Status:** Active
- **Contract:** [`docs/architecture/api/openapi.yaml`](../architecture/api/openapi.yaml)
- **OpenAPI version:** `3.1.2`
- **Validator:** Redocly CLI `2.43.2`

## Purpose

The OpenAPI document is a reviewed architecture contract. These automated checks
prevent invalid syntax, unresolved references, inconsistent schemas, invalid
examples, and accidental divergence from the approved HTTP conventions from being
merged.

The validation is local and deterministic after dependencies are installed. It does
not start the backend, call IGDB, use credentials, or make authenticated provider
requests.

## Prerequisites

- Node.js `24.x`;
- npm;
- repository development dependencies installed from the lock file.

Install the exact dependency graph:

```bash
npm ci
```

`npm ci` must be used in CI and is preferred locally. It installs the pinned
Redocly CLI version from `package-lock.json` without changing the lock file.

## Run all validations

Use the repository entry point:

```bash
bash scripts/validate-openapi.sh
```

The equivalent npm command is:

```bash
npm run validate:openapi
```

The command stops on the first failed phase and returns a non-zero exit code.

After a contract change passes validation, regenerate the human-readable reference
using the [OpenAPI web documentation tutorial](openapi-web-documentation.md).

## Validation phases

### 1. Syntax and OpenAPI structure

```bash
npm run validate:openapi:syntax
```

This phase parses the YAML, validates the OpenAPI document structure, and requires
the approved `openapi: 3.1.2` declaration. Malformed YAML, invalid OpenAPI objects,
or unsupported fields fail here.

Configuration:
[`tools/openapi-validation/syntax.redocly.yaml`](../../tools/openapi-validation/syntax.redocly.yaml)

### 2. Rules and approved conventions

```bash
npm run validate:openapi:lint
```

This phase applies the project rules in [`redocly.yaml`](../../redocly.yaml),
including:

- present, unique, URL-safe, `lowerCamelCase` operation IDs;
- summaries, defined single tags, and at least one `2xx` and `4xx` response per
  operation;
- valid path parameters and security declarations;
- no unused components;
- `X-Correlation-ID` on every documented response class;
- schema, reference, and example rules promoted to errors.

The optional Redocly `info-license` opinion is disabled because no repository or API
license has been approved. The private, non-commercial release constraint remains
documented in the contract and architecture sources; this configuration does not
invent a legal license.

### 3. References and schemas

```bash
npm run validate:openapi:schemas
```

This phase verifies that every `$ref` resolves and that component names, required
properties, enum types, numeric constraints, and schema structure are coherent.
Strict reference rules reject unsupported `$ref` shapes.

Configuration:
[`tools/openapi-validation/schemas.redocly.yaml`](../../tools/openapi-validation/schemas.redocly.yaml)

### 4. Examples

```bash
npm run validate:openapi:examples
```

This phase validates parameter, schema, request, response, success, empty, degraded,
conflict, authentication, and technical-error examples against their declared
schemas. Extra properties are not accepted when checking media-type examples.

Configuration:
[`tools/openapi-validation/examples.redocly.yaml`](../../tools/openapi-validation/examples.redocly.yaml)

## Continuous integration

The GitHub Actions documentation workflow installs Node.js and the locked npm
dependencies, then runs both documentation and OpenAPI validation on every push and
pull request. A validation failure blocks the workflow job.

## Updating validation tooling

1. Change the exact Redocly CLI version in `package.json`.
2. Run `npm install --package-lock-only` to update `package-lock.json`.
3. Run all four validation phases.
4. Review Redocly release notes for rule changes before accepting new findings or
   suppressing a rule.

Do not use floating `latest` versions in repository automation. Do not suppress a
failure without documenting why the contract or project rule should change.

## Troubleshooting

### Dependencies are missing

Run:

```bash
npm ci
```

### Node.js is incompatible

Install the approved Node.js 24 line in the WSL distribution and rerun `npm ci`.
The repository lives in the Linux filesystem, so Linux-native Node.js and npm avoid
mixed Windows/WSL path and executable-wrapper issues.

### A rule reports an intentional exception

First confirm that the approved API conventions permit the exception. Prefer a
narrow, documented rule configuration over an ignore file. Changes to public
contract semantics require explicit review rather than a lint suppression.
