import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const smoke = await readFile(new URL("./deployment-smoke.mjs", import.meta.url), "utf8");
const openApi = await readFile(new URL("../../../docs/architecture/api/openapi.yaml", import.meta.url), "utf8");

test("uses an explicit contract-valid releases view for the correlated smoke request", () => {
  assert.match(
    openApi,
    /ReleaseView:\n\s+name: view\n\s+in: query\n\s+required: true\n\s+schema: \{ \$ref: '#\/components\/schemas\/ReleaseView' \}/,
  );
  assert.match(openApi, /ReleaseView:\n\s+type: string\n\s+enum: \[recent, upcoming\]/);
  assert.match(smoke, /fetch\("\/api\/v1\/releases\?view=recent", \{/);
});
