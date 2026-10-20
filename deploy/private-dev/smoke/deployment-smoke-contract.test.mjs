import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const smoke = await readFile(new URL("./deployment-smoke.mjs", import.meta.url), "utf8");
const openApi = await readFile(new URL("../../../docs/architecture/api/openapi.yaml", import.meta.url), "utf8");

test("uses a contract-valid parameterless releases request for the correlated smoke request", () => {
  // Without a range the API reads the current week, so the request needs no parameter at all.
  for (const parameter of ["ReleaseFrom", "ReleaseTo", "ReleaseSort", "ReleaseIncludeApproximateDates"]) {
    assert.match(openApi, new RegExp(`${parameter}:\\n\\s+name: \\w+\\n\\s+in: query\\n\\s+required: false`));
  }
  assert.doesNotMatch(openApi, /name: view\n\s+in: query/);
  assert.match(smoke, /fetch\("\/api\/v1\/releases", \{/);
});
