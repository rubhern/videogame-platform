import { describe, expect, it } from "vitest";

import { platformIcon, regionIcon } from "./taxonomy-icons";

describe("taxonomy icons", () => {
  it("selects supplied marks for recognized product platform labels", () => {
    expect(platformIcon("android-id", "Android")).toBe("android");
    expect(platformIcon("ios-id", "iOS")).toBe("ios");
    expect(platformIcon("linux-id", "Linux")).toBe("linux");
    expect(platformIcon("mac-id", "Mac")).toBe("macos");
    expect(platformIcon("macos-id", "macOS")).toBe("macos");
    expect(platformIcon("meta-quest-id", "Meta Quest")).toBe("meta-quest");
    expect(platformIcon("meta-quest-2-id", "Meta Quest 2")).toBe("meta-quest");
    expect(platformIcon("meta-quest-3-id", "Meta Quest 3")).toBe("meta-quest");
    expect(platformIcon("switch-id", "Nintendo Switch")).toBe("nintendo-switch");
    expect(platformIcon("ps4-id", "PlayStation 4")).toBe("playstation-4");
    expect(platformIcon("ps5-id", "PlayStation 5")).toBe("playstation-5");
    expect(platformIcon("psvr2-id", "PlayStation VR2")).toBe("playstation-vr2");
    expect(platformIcon("xbox-one-id", "Xbox One")).toBe("xbox-one");
    expect(platformIcon("xbox-series-id", "Xbox Series X|S")).toBe("xbox-series-x-s");
    expect(platformIcon("xbox-series-alternative-id", "Xbox Series S|X")).toBe("xbox-series-x-s");
    expect(platformIcon("steamvr-id", "SteamVR")).toBe("steamvr");
    expect(platformIcon("steam-vr-id", "Steam VR")).toBe("steamvr");
  });

  it("keeps the approved seeded platform identities recognizable", () => {
    expect(platformIcon("10000000-0000-4000-8000-000000000001", "Other")).toBe("playstation-5");
    expect(platformIcon("10000000-0000-4000-8000-000000000002", "Other")).toBe("nintendo-switch-2");
    expect(platformIcon("10000000-0000-4000-8000-000000000003", "Other")).toBe("windows");
    expect(platformIcon("10000000-0000-4000-8000-000000000004", "Other")).toBe("xbox-series-x-s");
  });

  it("falls back to a generic platform marker for an unknown or newly acquired platform", () => {
    expect(platformIcon("99999999-9999-4999-8999-999999999999", "Unknown")).toBe("platform");
  });

  it("gives the seeded region identities semantically appropriate icons without assuming a flag", () => {
    expect(regionIcon("20000000-0000-4000-8000-000000000001", "Other")).toBe("worldwide");
    expect(regionIcon("20000000-0000-4000-8000-000000000002", "Other")).toBe("europe");
    expect(regionIcon("20000000-0000-4000-8000-000000000004", "Other")).toBe("north-america");
    expect(regionIcon("20000000-0000-4000-8000-000000000003", "Other")).toBe("region-unknown");
    expect(regionIcon("20000000-0000-4000-8000-000000000005", "Other")).toBe("japan");
  });

  it("selects supplied marks for acquired regions by their catalogue label", () => {
    // Acquired regions have an identity created per environment, so only the label is shared.
    expect(regionIcon("637c2274-1a20-41a9-8237-e0ea70eb146d", "Asia")).toBe("asia");
    expect(regionIcon("9f1d6ef3-e678-4974-a499-c1382336f7b6", "Corea")).toBe("korea");
    expect(regionIcon("de236094-c50d-40d2-b37d-3074043d1e6c", "Nueva Zelanda")).toBe("new-zealand");
    expect(regionIcon("cc21a890-2709-4561-bde7-b7775aa83978", "Brasil")).toBe("brazil");
    expect(regionIcon("1ca4ed64-a29f-4cce-a4f7-e6a34ea86bb5", "Australia")).toBe("australia");
  });

  it("recognizes the seeded regions by label where the context carries no seeded identity", () => {
    expect(regionIcon("worldwide", "Mundial")).toBe("worldwide");
    expect(regionIcon("europe", "Europa")).toBe("europe");
    expect(regionIcon("north-america", "Norteamérica")).toBe("north-america");
    expect(regionIcon("japan", "Japón")).toBe("japan");
    expect(regionIcon("unknown", "Sin región confirmada")).toBe("region-unknown");
  });

  it("falls back to a generic geographic marker for a region without a supplied mark", () => {
    expect(regionIcon("99999999-9999-4999-8999-999999999999", "China")).toBe("region-area");
    expect(regionIcon("99999999-9999-4999-8999-999999999999", "Middle East")).toBe("region-area");
  });
});
