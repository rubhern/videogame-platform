import { describe, expect, it } from "vitest";

import { clearedPlatforms, resolvePlatformFilter, togglePlatformSelection, type Interests } from "./interests";
import { createInterestsSwitch } from "./interests-switch";

const ready: Interests = {
  status: "ready",
  platforms: [
    { platformId: "switch-2", name: "Nintendo Switch 2" },
    { platformId: "ps5", name: "PlayStation 5" },
  ],
};

describe("interest resolution", () => {
  it("applies the preferred platforms by default", () => {
    expect(resolvePlatformFilter([], true, ready)).toEqual({ mode: "on", platformIds: ["switch-2", "ps5"] });
  });

  it("shows every platform while turned off, without forgetting them", () => {
    expect(resolvePlatformFilter([], false, ready)).toEqual({ mode: "off", platformIds: [] });
  });

  it("lets a manual selection take precedence with no hidden intersection", () => {
    expect(resolvePlatformFilter(["pc"], true, ready)).toEqual({ mode: "manual", platformIds: ["pc"] });
    expect(resolvePlatformFilter(["pc"], false, ready)).toEqual({ mode: "manual", platformIds: ["pc"] });
  });

  it.each<Interests>([{ status: "unavailable" }, { status: "none" }, { status: "pending" }])(
    "keeps the general experience when preferences do not apply (%o)",
    (interests) => {
      expect(resolvePlatformFilter([], true, interests)).toEqual({ mode: "inactive", platformIds: [] });
      expect(resolvePlatformFilter(["pc"], true, interests)).toEqual({ mode: "inactive", platformIds: ["pc"] });
    },
  );
});

describe("platform selection", () => {
  it("starts a manual selection from the visibly applied preferences and turns them off", () => {
    const filter = resolvePlatformFilter([], true, ready);
    expect(togglePlatformSelection(filter, "pc", ready)).toEqual({
      platformIds: ["switch-2", "ps5", "pc"],
      interestsEnabled: false,
    });
    expect(togglePlatformSelection(filter, "ps5", ready)).toEqual({ platformIds: ["switch-2"], interestsEnabled: false });
  });

  it("returns to the preferences when the selection equals them again", () => {
    const manual = resolvePlatformFilter(["switch-2"], false, ready);
    expect(togglePlatformSelection(manual, "ps5", ready)).toEqual({ platformIds: [], interestsEnabled: true });
  });

  it("shows every platform when a manual selection is emptied", () => {
    const manual = resolvePlatformFilter(["pc"], false, ready);
    expect(togglePlatformSelection(manual, "pc", ready)).toEqual({ platformIds: [], interestsEnabled: false });
  });

  it("keeps the plain multi-select without preferences", () => {
    const filter = resolvePlatformFilter(["pc"], true, { status: "none" });
    expect(togglePlatformSelection(filter, "pc", { status: "none" })).toEqual({ platformIds: [], interestsEnabled: null });
    expect(clearedPlatforms({ status: "unavailable" })).toEqual({ platformIds: [], interestsEnabled: null });
    expect(clearedPlatforms(ready)).toEqual({ platformIds: [], interestsEnabled: false });
  });
});

describe("document-wide switch", () => {
  it("starts on and notifies only real changes", () => {
    const store = createInterestsSwitch();
    const seen: boolean[] = [];
    store.subscribe(() => seen.push(store.enabled()));
    expect(store.enabled()).toBe(true);
    store.set(true);
    store.set(false);
    expect(seen).toEqual([false]);
  });
});
