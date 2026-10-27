import { describe, expect, it } from "vitest";

import { DEFAULT_RETURN, gameLinkState, historyEntry, readReturnContext } from "./return-context";

const at = (path: string, state: unknown = null, key = "entry-1") => {
  const url = new URL(path, "https://gameometro.test");
  return { pathname: url.pathname, search: url.search, key, state };
};

describe("game page return context", () => {
  it.each([
    ["/", "Volver a Destacados"],
    ["/lanzamientos/destacados?week=2026-W42", "Volver a Destacados"],
    ["/?month=2026-12", "Volver a Destacados"],
    ["/lanzamientos?from=2026-08-01&to=2026-08-31&sort=hype&page=2", "Volver a lanzamientos"],
    ["/lanzamientos/calendario?month=2026-11&day=2026-11-13", "Volver al calendario"],
    ["/search?q=zelda&page=3", "Volver a resultados"],
    ["/mis-puntuaciones", "Volver a mis puntuaciones"],
    ["/mis-puntuaciones/estadisticas", "Volver a tus estadísticas"],
    ["/topometro", "Volver al TOPómetro"],
    ["/topometro?source=gameometro", "Volver al TOPómetro"],
  ])("names and keeps the whole origin %s", (path, label) => {
    const context = readReturnContext(gameLinkState(at(path)));

    expect(context?.label).toBe(label);
    expect(context?.path).toBe(path.replace(/^\/(?=\?|$)/, "/lanzamientos/destacados"));
    expect(context?.state).toEqual({ restoreEntry: "entry-1" });
  });

  it("migrates a stored legacy release-list origin with its filters to the explorer", () => {
    expect(readReturnContext({ returnTo: {
      path: "/?view=upcoming&weeks=4&platformIds=pc&regionIds=europe&pageSize=6", entry: "old-entry",
    } })).toEqual({
      origin: "releases", path: "/lanzamientos?platformIds=pc&regionIds=europe&pageSize=6",
      label: "Volver a lanzamientos", state: { restoreEntry: "old-entry" },
    });
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
  ])("rejects %s and lets the page fall back to Destacados", (_, state) => {
    expect(readReturnContext(state)).toBeNull();
    expect(DEFAULT_RETURN).toEqual({ path: "/lanzamientos/destacados", label: "Volver a Destacados" });
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
