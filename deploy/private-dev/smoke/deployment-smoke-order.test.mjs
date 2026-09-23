import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const source = await readFile(new URL("./deployment-smoke.mjs", import.meta.url), "utf8");

test("checks diagnostic metrics after the real releases/browser request", () => {
  const releasesBrowserCheck = source.indexOf('completedChecks.push("releases-api", "browser-shell")');
  const diagnosticMetricsCheck = source.indexOf(
    'const metrics = await jsonResponse(await management.get("/actuator/metrics"), "metrics");',
  );

  assert.notEqual(releasesBrowserCheck, -1);
  assert.notEqual(diagnosticMetricsCheck, -1);
  assert(releasesBrowserCheck < diagnosticMetricsCheck);
  assert(
    source.includes('["http.server.requests", "jvm.memory.used", "jdbc.connections.active"]'),
  );
  assert(source.includes("required diagnostic metric is absent: ${name}"));
});
