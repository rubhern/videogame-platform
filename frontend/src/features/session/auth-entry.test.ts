import { describe, expect, it } from "vitest";
import { authenticationStartUrl, safeReturnTo } from "./auth-entry";

describe("account entry navigation", () => {
  it.each(["/", "/?view=upcoming&weeks=4", "/search?q=Final+Fantasy&page=2", "/mis-puntuaciones", "/games/game-1/a-game?platformId=pc&regionId=europe"])("preserves %s", (path) => {
    expect(safeReturnTo(path)).toBe(path);
    expect(new URL(authenticationStartUrl(path), "https://app.example").searchParams.get("returnTo")).toBe(path);
  });
  it.each(["https://evil.example", "//evil.example", "/\\evil.example", "/%2f%2fevil.example", "/games/../auth/start", "/auth/start", "/api/v1/session", "/login", "/search?redirect=https://evil.example", "/search?q=%0d%0aLocation:evil", "/search?q=%250d", "/search#evil", "/search?q=%5cevil", "/search?q=" + "a".repeat(2048)])("falls back safely for %s", (path) => {
    expect(safeReturnTo(path)).toBe("/");
  });
  it("marks registration intent without creating a second identity flow", () => {
    const url = new URL(authenticationStartUrl("/search?q=zelda", true), "https://app.example");
    expect(url.pathname).toBe("/auth/start");
    const params = url.searchParams;
    expect(params.get("intent")).toBe("register");
    expect(params.get("returnTo")).toBe("/search?q=zelda");
  });
});
