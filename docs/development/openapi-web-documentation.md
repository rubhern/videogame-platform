# OpenAPI web documentation

- **Status:** Active
- **Source contract:** [`docs/architecture/api/openapi.yaml`](../architecture/api/openapi.yaml)
- **Generated site:** [`docs/architecture/api/reference/index.html`](../architecture/api/reference/index.html)
- **Generator:** Redocly CLI `2.43.2` with Redoc CE

## Purpose and ownership

The generated HTML is the human-readable web reference for the approved learning
MVP API contract. It presents paths, operations, parameters, security requirements,
headers, schemas, examples, and error responses from the OpenAPI source.

`openapi.yaml` remains the authoritative contract. The HTML file is a generated,
reviewable artefact and must never be edited by hand. Regenerate it after every
contract change.

The build disables Google Fonts and embeds the API description and prerendered
markup in one static HTML file. The generated page loads its versioned Redoc runtime
from the Redocly CDN with a Subresource Integrity check. Generation does not call
the backend, IGDB, or any authenticated service.

## Prerequisites

- Node.js `24.x`;
- npm;
- repository development dependencies installed from `package-lock.json`.

From the repository root, install the exact dependency graph:

```bash
npm ci
```

Use Linux-native Node.js and npm when working inside WSL. Mixing Windows npm with a
repository stored in the WSL filesystem can produce incompatible executable
wrappers in `node_modules`.

## Regenerate the web documentation

Run the repository entry point:

```bash
bash scripts/build-openapi-docs.sh
```

The script performs these steps:

1. checks the Node.js, npm, and Redocly installation;
2. runs all syntax, lint, reference, schema, and example validations;
3. builds the static Redoc HTML;
4. normalizes generated line endings and trailing whitespace;
5. verifies that the generated file exists and is not empty.

The generated file is:

```text
docs/architecture/api/reference/index.html
```

If the contract has already been validated and only the generation command is
needed, run:

```bash
npm run build:openapi-docs
```

## View the generated site

The output is a static file. Open `docs/architecture/api/reference/index.html`
directly in a browser, or serve the repository root with a local static server:

```bash
python3 -m http.server 8000
```

Then browse to:

```text
http://localhost:8000/docs/architecture/api/reference/
```

The local server is optional and must be stopped with `Ctrl+C` when no longer
needed. The page requires network access to download the Redoc runtime from its
versioned CDN URL; fully offline delivery is outside the current scope.

## Review a regenerated artefact

After generation:

1. open the page and confirm that navigation, search, schemas, and examples render;
2. confirm that all eight approved operations are present;
3. inspect the generated change:

   ```bash
   git diff --stat -- docs/architecture/api/reference/index.html
   git diff --numstat -- docs/architecture/api/reference/index.html
   ```

4. run the repository documentation validation:

   ```bash
   bash scripts/validate-docs.sh
   ```

Do not review the minified HTML line by line. Review the authoritative OpenAPI diff,
the successful validation output, the rendered page, and the generated file size.

## Continuous integration

The documentation workflow regenerates the HTML after validating the contract and
uses `git diff --exit-code` on the generated file. CI fails when the committed web
reference is stale, so a contract change and its regenerated documentation must be
committed together.

## Change procedure

1. Update `docs/architecture/api/openapi.yaml`.
2. Run `bash scripts/build-openapi-docs.sh`.
3. Inspect the OpenAPI diff and the rendered HTML.
4. Run `bash scripts/validate-docs.sh`.
5. Commit the contract and generated HTML in the same change.

Only change the output path, page title, font policy, or Redoc theme through the
`build:openapi-docs` command in `package.json`. Document material presentation or
hosting decisions before introducing custom templates or external assets.

## Troubleshooting

### Validation fails before generation

Fix the OpenAPI finding first. The build script intentionally does not generate new
documentation from an invalid contract.

### Dependencies are missing

Run `npm ci` from the repository root, then retry the build script.

### The generated file differs without an OpenAPI change

Confirm that `package.json` and `package-lock.json` still select the approved
Redocly version. Tool upgrades can change generated markup and must be reviewed as a
deliberate dependency update.

### The page is blank

Regenerate the file, confirm it is non-empty, and try the local HTTP server described
above. Check the browser console for blocked local-file or content-security-policy
errors before changing the OpenAPI contract.
