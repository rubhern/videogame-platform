import { afterEach, describe, expect, it } from "vitest";

import { CONSENT_STORAGE_KEY, createConsentStore } from "./consent";

const DAY = 24 * 60 * 60 * 1000;

afterEach(() => window.localStorage.clear());

describe("consent store", () => {
  it("stores nothing until the visitor chooses", () => {
    const store = createConsentStore(() => 0);
    expect(store.state()).toBe("unset");
    expect(window.localStorage.getItem(CONSENT_STORAGE_KEY)).toBeNull();
  });

  it("remembers a choice and asks again after a year", () => {
    let now = Date.parse("2026-11-09T10:00:00Z");
    createConsentStore(() => now).choose("denied");
    expect(createConsentStore(() => now + 364 * DAY).state()).toBe("denied");
    now += 366 * DAY;
    expect(createConsentStore(() => now).state()).toBe("unset");
  });

  it("reads a malformed entry as not chosen", () => {
    window.localStorage.setItem(CONSENT_STORAGE_KEY, "{\"choice\":\"granted\"}");
    expect(createConsentStore().state()).toBe("unset");
    window.localStorage.setItem(CONSENT_STORAGE_KEY, "not json");
    expect(createConsentStore().state()).toBe("unset");
  });

  it("opens a review and closes it with the next choice", () => {
    const store = createConsentStore();
    store.choose("granted");
    store.review();
    expect(store.reviewing()).toBe(true);
    store.choose("denied");
    expect(store.reviewing()).toBe(false);
    expect(store.state()).toBe("denied");
  });
});
