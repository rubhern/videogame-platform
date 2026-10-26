import { QueryClient } from "@tanstack/react-query";
import type { LoaderFunctionArgs } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createInterestsSwitch } from "../features/platform-preferences/interests-switch";
import { routeReadiness } from "./route-readiness";

type Preferences = { configured: boolean; platforms: { platformId: string; name: string }[] };

function stub(session: { authenticated: boolean }, preferences: Preferences) {
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const url = new URL((input as Request).url);
    if (url.pathname === "/api/v1/session") {
      return Response.json(session.authenticated ? { authenticated: true, csrfToken: "csrf" } : session);
    }
    if (url.pathname === "/api/v1/me/platform-preferences") return Response.json(preferences);
    return Response.json({ code: "INTERNAL_ERROR" }, { status: 500 });
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function load(loader: ReturnType<typeof routeReadiness>, path: string, params: Record<string, string> = {}) {
  return loader({ request: new Request(`http://localhost${path}`), params } as unknown as LoaderFunctionArgs);
}

function paths(fetchMock: ReturnType<typeof stub>): string[] {
  return fetchMock.mock.calls.map((call) => {
    const url = new URL((call[0] as Request).url);
    return url.pathname + url.search;
  });
}

afterEach(() => vi.unstubAllGlobals());

describe("first-run platform choice", () => {
  it("is offered once, on the way into discovery, keeping the destination", async () => {
    stub({ authenticated: true }, { configured: false, platforms: [] });
    const loader = routeReadiness(new QueryClient({ defaultOptions: { queries: { retry: false } } }), createInterestsSwitch());

    const offer = (await load(loader, "/lanzamientos?sort=hype")) as Response;
    expect(offer.status).toBe(302);
    expect(offer.headers.get("Location")).toBe("/bienvenida?returnTo=%2Flanzamientos%3Fsort%3Dhype");
    // Leaving the welcome without choosing never bounces the visitor back in this document.
    await expect(load(loader, "/")).resolves.toBeNull();
  });

  it("offers the first-run choice at the canonical featured period and does not read legacy aliases twice", async () => {
    const fetchMock = stub({ authenticated: true }, { configured: false, platforms: [] });
    const loader = routeReadiness(new QueryClient({ defaultOptions: { queries: { retry: false } } }), createInterestsSwitch());
    await expect(load(loader, "/?month=2026-10")).resolves.toBeNull();
    expect(paths(fetchMock)).toEqual([]);
    const offer = await load(loader, "/lanzamientos/destacados?month=2026-10") as Response;
    expect(offer.headers.get("Location")).toBe("/bienvenida?returnTo=%2Flanzamientos%2Fdestacados%3Fmonth%3D2026-10");
  });

  it("never interrupts a rating return or a personal page", async () => {
    const fetchMock = stub({ authenticated: true }, { configured: false, platforms: [] });
    const loader = routeReadiness(new QueryClient({ defaultOptions: { queries: { retry: false } } }), createInterestsSwitch());

    await expect(load(loader, "/games/game-1/a-game?auth=resumed", { gameId: "game-1" })).resolves.toBeNull();
    await expect(load(loader, "/mis-plataformas")).resolves.toBeNull();
    expect(paths(fetchMock).some((path) => path.startsWith("/api/v1/me/platform-preferences"))).toBe(false);
  });

  it("is not offered once a choice exists, and the first read is already the personal one", async () => {
    const fetchMock = stub(
      { authenticated: true },
      { configured: true, platforms: [{ platformId: "ps5", name: "PlayStation 5" }] },
    );
    const loader = routeReadiness(new QueryClient({ defaultOptions: { queries: { retry: false } } }), createInterestsSwitch());

    await expect(load(loader, "/lanzamientos/destacados?week=2026-W40")).resolves.toBeNull();
    expect(paths(fetchMock)).toContain("/api/v1/featured-releases?week=2026-W40&platformIds=ps5");
  });

  it("asks an anonymous visitor nothing personal", async () => {
    const fetchMock = stub({ authenticated: false }, { configured: false, platforms: [] });
    const loader = routeReadiness(new QueryClient({ defaultOptions: { queries: { retry: false } } }), createInterestsSwitch());

    await expect(load(loader, "/lanzamientos")).resolves.toBeNull();
    expect(paths(fetchMock).filter((path) => path.startsWith("/api/v1/me"))).toHaveLength(0);
  });
});

describe("personal statistics readiness", () => {
  it("hands the bounded private read to only the session that read it, before restoring scroll", async () => {
    const data={total:0};
    const fetchMock=vi.fn<typeof fetch>().mockImplementation(async input => {
      const url=new URL((input as Request).url);
      return Response.json(url.pathname.endsWith("/session") ? {authenticated:true,csrfToken:"session-proof"} : data);
    });
    vi.stubGlobal("fetch",fetchMock);
    const loader=routeReadiness(new QueryClient({defaultOptions:{queries:{retry:false}}}),createInterestsSwitch());
    await expect(load(loader,"/mis-puntuaciones/estadisticas")).resolves.toEqual({data,sessionProof:"session-proof"});
    expect(paths(fetchMock)).toEqual(["/api/v1/session","/api/v1/me/rating-insights"]);
  });

  it("does not request statistics for an anonymous session", async () => {
    const fetchMock=stub({authenticated:false},{configured:false,platforms:[]});
    const loader=routeReadiness(new QueryClient({defaultOptions:{queries:{retry:false}}}),createInterestsSwitch());
    await expect(load(loader,"/mis-puntuaciones/estadisticas")).resolves.toBeNull();
    expect(paths(fetchMock)).toEqual(["/api/v1/session"]);
  });
});
