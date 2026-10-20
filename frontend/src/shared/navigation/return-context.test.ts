import { describe, expect, it } from "vitest";

import { DEFAULT_RETURN, gameLinkState, historyEntry, readReturnContext } from "./return-context";

const at = (path: string, state: unknown = null, key = "entry-1") => {
  const url = new URL(path, "https://gameometro.test");
  return { pathname: url.pathname, search: url.search, key, state };
};

describe("game page return context", () => {
  it.each([
    ["/", "Volver al TOPómetro"],
    ["/?month=2026-12", "Volver al TOPómetro"],
    ["/lanzamientos?from=2026-08-01&to=2026-08-31&sort=hype&page=2", "Volver a lanzamientos"],
    ["/search?q=zelda&page=3", "Volver a resultados"],
    ["/mis-puntuaciones", "Volver a mis puntuaciones"],
    ["/mis-puntuaciones/estadisticas", "Volver a tus estadísticas"],
  ])("names and keeps the whole origin %s", (path, label) => {
    const context = readReturnContext(gameLinkState(at(path)));

    expect(context?.label).toBe(label);
    expect(context?.path).toBe(path);
    expect(context?.state).toEqual({ restoreEntry: "entry-1" });
  });

  it("carries no origin from screens without a way back", () => {
    expect(gameLinkState(at("/games/game-1/a-game"))).toBeUndefined();
    expect(gameLinkState(at("/administracion/catalogo"))).toBeUndefined();
    expect(gameLinkState(at("/aviso-legal"))).toBeUndefined();
  });

  it.each([
    ["an external URL", { returnTo: { path: "https://evil.example/", entry: "entry-1" } }],
    ["a protocol-relative URL", { returnTo: { path: "//evil.example/lanzamientos", entry: "entry-1" } }],
    ["a backslash path", { returnTo: { path: "/\\evil.example", entry: "entry-1" } }],
    ["a server route", { returnTo: { path: "/auth/start?returnTo=/", entry: "entry-1" } }],
    ["an unknown screen", { returnTo: { path: "/games/game-1/a-game", entry: "entry-1" } }],
    ["a malformed entry", { returnTo: { path: "/lanzamientos", entry: "<script>" } }],
    ["a non-string path", { returnTo: { path: 7, entry: "entry-1" } }],
    ["no origin at all", null],
    ["a foreign shape", "returnTo"],
  ])("rejects %s and lets the page fall back to TOPómetro", (_, state) => {
    expect(readReturnContext(state)).toBeNull();
    expect(DEFAULT_RETURN).toEqual({ path: "/", label: "Volver al TOPómetro" });
  });

  it("drops a fragment and keeps only the path and query", () => {
    expect(
      readReturnContext({ returnTo: { path: "/search?q=a#evil", entry: "entry-1" } })?.path,
    ).toBe("/search?q=a");
  });

  it("carries the explorer's day frame across the game page", () => {
    const origin = at("/lanzamientos?from=2026-08-14&to=2026-08-14", {
      dayFrame: { from: "2026-08-01", to: "2026-08-31" },
    });

    expect(readReturnContext(gameLinkState(origin))?.state).toEqual({
      restoreEntry: "entry-1",
      dayFrame: { from: "2026-08-01", to: "2026-08-31" },
    });
    expect(
      readReturnContext({
        returnTo: { path: "/lanzamientos", entry: "entry-1", dayFrame: { from: "x", to: 1 } },
      })?.state,
    ).toEqual({ restoreEntry: "entry-1" });
  });

  it("keys a returned entry by the entry it returns to, every other entry by itself", () => {
    expect(historyEntry(at("/lanzamientos", { restoreEntry: "origin-7" }, "new-entry"))).toBe("origin-7");
    expect(historyEntry(at("/lanzamientos", { restoreEntry: "../../x" }, "new-entry"))).toBe("new-entry");
    expect(historyEntry(at("/lanzamientos", null, "new-entry"))).toBe("new-entry");
    // A further game link from a returned entry keeps pointing at the original entry.
    expect(
      gameLinkState(at("/lanzamientos", { restoreEntry: "origin-7" }, "new-entry"))?.returnTo.entry,
    ).toBe("origin-7");
  });
});
