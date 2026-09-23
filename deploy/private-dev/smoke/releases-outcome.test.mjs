import assert from "node:assert/strict";
import test from "node:test";

import { classifyReleasesOutcome } from "./releases-outcome.mjs";

test("accepts published release pages with or without items", () => {
  assert.deepEqual(
    classifyReleasesOutcome(200, { items: [], page: { totalItems: 0 } }),
    { empty: true, state: "published" },
  );
  assert.deepEqual(
    classifyReleasesOutcome(200, { items: [{ gameId: "game" }], page: { totalItems: 1 } }),
    { empty: false, state: "published" },
  );
});

test("accepts the approved state before any catalogue publication exists", () => {
  assert.deepEqual(
    classifyReleasesOutcome(503, { code: "CATALOGUE_NOT_READY" }),
    { empty: true, state: "not-ready" },
  );
});

test("rejects technical failures and malformed success bodies", () => {
  assert.throws(() => classifyReleasesOutcome(503, { code: "CATALOGUE_READ_FAILED" }));
  assert.throws(() => classifyReleasesOutcome(200, { items: [] }));
});
