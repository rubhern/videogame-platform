import { describe, expect, it } from "vitest";

import { safeWelcomeReturn, welcomePath } from "./welcome-return";

describe("welcome destination", () => {
  it.each(["/lanzamientos/destacados", "/lanzamientos/calendario?month=2026-11", "/", "/?week=2026-W42&interests=false", "/lanzamientos", "/lanzamientos?from=2026-10-01&to=2026-10-31"])(
    "keeps the discovery destination %s",
    (path) => {
      expect(safeWelcomeReturn(path)).toBe(path);
    },
  );

  it.each([null, "", "https://evil.example", "//evil.example", "/\\evil", "/search?q=zelda", "/games/x/y", "/mis-puntuaciones", "/lanzamientos#x", "/%2f%2fevil", "/auth/start"])(
    "falls back to Destacados for %s",
    (path) => {
      expect(safeWelcomeReturn(path)).toBe("/lanzamientos/destacados");
    },
  );

  it("names the destination only when it is not Destacados's default view", () => {
    expect(welcomePath("/lanzamientos/destacados")).toBe("/bienvenida");
    expect(welcomePath("/lanzamientos?sort=hype")).toBe("/bienvenida?returnTo=%2Flanzamientos%3Fsort%3Dhype");
  });
});
