import { describe, expect, it } from "vitest";

import { readRequestedRun, validateWindow } from "./synchronization-request-state";

describe("synchronization request state", () => {
  it("validates the contract's bounds before any request", () => {
    expect(validateWindow("2026-07-01", "2026-12-31")).toEqual({});
    expect(validateWindow("2026-01-01", "2026-07-03")).toEqual({});
    expect(validateWindow("2026-01-01", "2026-07-04").to).toMatch(/184 días/);
    expect(validateWindow("2026-02-01", "2026-01-31").to).toMatch(/anterior/);
    expect(validateWindow("1949-12-31", "1950-01-01").from).toMatch(/1950 y 2099/);
    expect(validateWindow("2099-12-01", "2100-01-01").to).toMatch(/1950 y 2099/);
    expect(validateWindow("", "")).toEqual({ from: "Indica la fecha de inicio.", to: "Indica la fecha final." });
  });

  it("follows the requested run through the ordinary status only", () => {
    const requested = {
      run: { runId: "r", from: "2026-10-01", to: "2026-10-31", startedAt: "2026-10-18T10:00:00Z" },
      acceptedAt: 1_000,
    };
    expect(readRequestedRun(requested, undefined, 0)).toEqual({ phase: "accepted" });
    expect(readRequestedRun(requested, { recentRuns: [] }, 500)).toEqual({ phase: "accepted" });
    expect(readRequestedRun(requested, { recentRuns: [] }, 2_000)).toEqual({ phase: "missing" });
    expect(readRequestedRun(requested, {
      currentRun: { runId: "r", from: "2026-10-01", to: "2026-10-31", startedAt: "x", lastHeartbeatAt: "x", stalled: true },
      recentRuns: [],
    }, 2_000)).toEqual({ phase: "running", stalled: true });
    const finished = {
      runId: "r", from: "2026-10-01", to: "2026-10-31", startedAt: "x", completedAt: "y",
      result: "failed" as const, outcomeCode: "SYNCHRONIZATION_FAILED",
    };
    expect(readRequestedRun(requested, { recentRuns: [finished] }, 2_000)).toEqual({ phase: "finished", run: finished });
  });
});
