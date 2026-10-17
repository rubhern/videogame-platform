import { describe, expect, it } from "vitest";

import { gameDetailsFixture } from "../../test/game-details-fixture";
import { groupByContext } from "./release-groups";

// The fixture's contract type, which keeps an unknown date's explicit null value.
type Release = ReturnType<typeof gameDetailsFixture>["releases"][number];

function release(id: string, platform: string, changes: Partial<Release> = {}): Release {
  const [base] = gameDetailsFixture().releases;
  if (!base) throw new Error("Fixture needs a release");
  return {
    ...base,
    releaseId: id,
    platform: { platformId: platform, name: platform },
    region: { regionId: "worldwide", name: "Mundial" },
    releaseDate: { precision: "day", value: "2026-09-24" },
    status: "released",
    stage: "full_release",
    ...changes,
  };
}

const ids = (groups: ReturnType<typeof groupByContext>) =>
  groups.map((group) => group.releases.map((member) => member.releaseId));

describe("release groups", () => {
  it("states one shared context once, with its platforms in the API's order", () => {
    const groups = groupByContext([
      release("ps5", "PlayStation 5"),
      release("mac", "Mac"),
      release("pc", "Windows PC"),
      release("xbox", "Xbox Series X|S"),
    ]);
    expect(ids(groups)).toEqual([["ps5", "mac", "pc", "xbox"]]);
  });

  it.each([
    ["another day", { releaseDate: { precision: "day", value: "2026-09-25" } }],
    ["another precision of the same period", { releaseDate: { precision: "month", value: "2026-09" } }],
    ["an unknown date", { releaseDate: { precision: "unknown", value: null } }],
    ["another region", { region: { regionId: "europe", name: "Europa" } }],
    ["another status", { status: "delayed" }],
    ["another stage", { stage: "early_access" }],
    ["a pending review", { reviewStatus: "required" }],
    ["verified evidence", { verificationLevel: "verified" }],
  ] as const)("never shares a context with %s", (_, changes) => {
    const groups = groupByContext([
      release("ps5", "PlayStation 5", { verificationLevel: "provider_only" }),
      release("pc", "Windows PC", { verificationLevel: "provider_only", ...changes }),
    ]);
    // Each keeps a group of its own, whichever comes first in the calendar.
    expect(ids(groups).sort()).toEqual([["pc"], ["ps5"]]);
  });

  it("reads as a calendar: earliest period first, unknown dates last, ties in the API's order", () => {
    const groups = groupByContext([
      release("unknown", "Xbox Series X|S", { releaseDate: { precision: "unknown", value: null } }),
      release("q2", "Windows PC", { releaseDate: { precision: "quarter", value: "2027-Q2" } }),
      release("day", "PlayStation 5", { releaseDate: { precision: "day", value: "2027-03-31" } }),
      release("year", "Nintendo Switch 2", { releaseDate: { precision: "year", value: "2027" } }),
      release("month", "Mac", { releaseDate: { precision: "month", value: "2027-01" } }),
    ]);
    // A year and its January both start on 1 January: they keep the order they arrived in.
    expect(ids(groups)).toEqual([["year"], ["month"], ["day"], ["q2"], ["unknown"]]);
  });

  it("keeps every release once, with its context's first appearance breaking ties", () => {
    const releases = [
      release("ps4-jp", "PlayStation 4", { region: { regionId: "jp", name: "Japón" } }),
      release("ps4-eu", "PlayStation 4", { region: { regionId: "eu", name: "Europa" } }),
      release("switch-eu", "Nintendo Switch", { region: { regionId: "eu", name: "Europa" } }),
      release("switch-jp", "Nintendo Switch", {
        region: { regionId: "jp", name: "Japón" },
        releaseDate: { precision: "day", value: "2023-08-31" },
      }),
      release("pc-eu", "Windows PC", { region: { regionId: "eu", name: "Europa" } }),
    ];
    const groups = groupByContext(releases);
    // The earlier Japanese day leads; Japan and Europe share a day and keep their API order.
    expect(ids(groups)).toEqual([["switch-jp"], ["ps4-jp"], ["ps4-eu", "switch-eu", "pc-eu"]]);
    expect(groups.flatMap((group) => group.releases)).toHaveLength(releases.length);
    // Members are the API's own records, never copies with a merged value.
    expect(groups[2]?.releases[1]).toBe(releases[2]);
  });
});
