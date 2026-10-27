import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";

import { PlatformDestinations } from "../features/platforms/platform-card";
import type { PlatformContext } from "../features/platforms/platform-context";
import { platformDestinations } from "../features/platforms/platform-destinations";
import { renderApp } from "../test/render-app";

const directory = [
  { platformId: "android-id", name: "Android" },
  { platformId: "10000000-0000-4000-8000-000000000002", name: "Nintendo Switch 2" },
  { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" },
  { platformId: "ps4-id", name: "PlayStation 4" },
  { platformId: "steamvr-id", name: "SteamVR" },
  { platformId: "10000000-0000-4000-8000-000000000003", name: "Windows PC" },
  { platformId: "xone-id", name: "Xbox One" },
];

function stubPlatforms(response: () => Response) {
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const url = input instanceof Request ? input.url : String(input);
    if (url.includes("/session")) return Response.json({ authenticated: false });
    if (url.includes("/platforms")) return response();
    return Response.json({}, { status: 404 });
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function problem(status: number, code: string) {
  return () => Response.json(
    { type: "about:blank", title: "Fallo", status, code, correlationId: "corr-123" },
    { status, headers: { "Content-Type": "application/problem+json" } },
  );
}

const featuredList = () => screen.getByRole("list", { name: "Plataformas destacadas" });
const others = () => screen.getByRole("region", { name: "Otras plataformas" });

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("Plataformas", () => {
  it("presents the four featured platforms with three destinations each, linking only the live ones", async () => {
    stubPlatforms(() => Response.json({ items: directory }));
    renderApp("/plataformas");

    expect(await screen.findByRole("heading", { level: 1, name: "Plataformas" })).toBeInTheDocument();
    expect(document.title).toBe("Plataformas · Gameómetro");
    const cards = within(featuredList()).getAllByRole("article");
    expect(cards.map((card) => within(card).getByRole("heading", { level: 2 }).textContent)).toEqual([
      "Nintendo Switch 2", "PlayStation 5", "Xbox Series X|S", "Windows PC",
    ]);
    for (const card of cards) {
      const name = within(card).getByRole("heading", { level: 2 }).textContent ?? "";
      const destinations = within(card).getByRole("list", { name: `Destinos de ${name}` });
      expect(within(destinations).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
        "Explorar catálogo", "TOPómetro", "Próximos destacados",
      ]);
      // Próximos destacados (#388), TOPómetro (#387) and Explorar catálogo (#389) are live for
      // every platform, by their own shareable paths.
      expect(within(destinations).getByRole("link", { name: "Próximos destacados" }).getAttribute("href"))
        .toMatch(/^\/plataformas\/[a-z0-9-]+\/proximos$/);
      expect(within(destinations).getByRole("link", { name: "TOPómetro" }).getAttribute("href"))
        .toMatch(/^\/plataformas\/[a-z0-9-]+\/topometro$/);
      expect(within(destinations).getByRole("link", { name: "Explorar catálogo" }).getAttribute("href"))
        .toMatch(/^\/plataformas\/[a-z0-9-]+\/catalogo$/);
      // Logos are decorative: the heading alone names the platform.
      expect(within(card).queryAllByRole("img")).toHaveLength(0);
    }
    for (const card of cards) {
      const catalogue = within(card).getByRole("link", { name: "Explorar catálogo" });
      const name = within(card).getByRole("heading", { level: 2 }).textContent ?? "";
      expect(within(card).getByRole("link", { name })).toHaveAttribute("href", catalogue.getAttribute("href"));
      expect(within(card).getByRole("link", { name: `Explorar catálogo de ${name}` })).toHaveAttribute("href", catalogue.getAttribute("href"));
    }
    // The header's Plataformas entry is the current page.
    expect(within(screen.getByRole("navigation", { name: "Principal" })).getByRole("button", { name: "Plataformas" }))
      .toHaveAttribute("aria-current", "page");
  });

  it("states honestly when a featured platform has no games yet", async () => {
    stubPlatforms(() => Response.json({ items: directory }));
    renderApp("/plataformas");

    const card = (name: string) => within(featuredList()).getAllByRole("article")
      .find((article) => within(article).queryByRole("heading", { level: 2, name }) !== null) as HTMLElement;
    expect(await within(card("Xbox Series X|S")).findByText("Aún sin juegos en Gameómetro")).toBeVisible();
    expect(within(card("PlayStation 5")).queryByText("Aún sin juegos en Gameómetro")).not.toBeInTheDocument();
  });

  it("offers the owner's other platforms and opens one's destinations in place, shareable", async () => {
    const user = userEvent.setup();
    stubPlatforms(() => Response.json({ items: directory }));
    const { router } = renderApp("/plataformas");

    const list = await within(others()).findByRole("list");
    // SteamVR has games but is not among the owner's other platforms.
    expect(within(list).getAllByRole("button").map((button) => button.getAttribute("aria-label"))).toEqual([
      "Más destinos de Android", "Más destinos de PlayStation 4", "Más destinos de Xbox One",
    ]);
    const ps4 = within(list).getByRole("button", { name: "Más destinos de PlayStation 4" });
    expect(ps4).toHaveAttribute("aria-expanded", "false");
    expect(within(others()).queryByRole("list", { name: /^Destinos de/ })).not.toBeInTheDocument();

    await user.click(ps4);

    expect(router.state.location.pathname).toBe("/plataformas");
    expect(router.state.location.search).toBe("?plataforma=ps4-id");
    expect(ps4).toHaveAttribute("aria-expanded", "true");
    expect(ps4).toHaveFocus();
    const destinations = within(others()).getByRole("list", { name: "Destinos de PlayStation 4" });
    expect(ps4).toHaveAttribute("aria-controls", destinations.id);
    // The destinations open inside the chosen platform's own item.
    expect(ps4.closest("li")).toContainElement(destinations);
    expect(destinations).toHaveTextContent(
      "Explorar catálogoTOPómetroPróximos destacados",
    );
    expect(within(destinations).getByRole("link", { name: "Próximos destacados" }))
      .toHaveAttribute("href", "/plataformas/ps4-id/proximos");
    expect(within(destinations).getByRole("link", { name: "TOPómetro" }))
      .toHaveAttribute("href", "/plataformas/ps4-id/topometro");
    expect(within(destinations).getByRole("link", { name: "Explorar catálogo" }))
      .toHaveAttribute("href", "/plataformas/ps4-id/catalogo");

    await user.click(within(list).getByRole("button", { name: "Más destinos de Android" }));
    expect(router.state.location.search).toBe("?plataforma=android-id");
    expect(ps4).toHaveAttribute("aria-expanded", "false");
    expect(within(others()).getAllByRole("list", { name: /^Destinos de/ })).toHaveLength(1);

    await act(() => router.navigate(-1));
    expect(ps4).toHaveAttribute("aria-expanded", "true");

    await user.click(ps4);
    expect(router.state.location.search).toBe("");
    expect(within(others()).queryByRole("list", { name: /^Destinos de/ })).not.toBeInTheDocument();
  });

  it("opens a shared platform without moving focus", async () => {
    stubPlatforms(() => Response.json({ items: directory }));
    renderApp("/plataformas?plataforma=xone-id");

    expect(await within(others()).findByRole("list", { name: "Destinos de Xbox One" })).toBeInTheDocument();
    expect(within(others()).getByRole("button", { name: "Más destinos de Xbox One" })).toHaveAttribute("aria-expanded", "true");
    expect(within(others()).getByRole("button", { name: "Más destinos de Xbox One" })).not.toHaveFocus();
  });

  it.each(["dreamcast", "steamvr-id"])("never opens %s, which is not on offer", async (value) => {
    stubPlatforms(() => Response.json({ items: directory }));
    renderApp(`/plataformas?plataforma=${value}`);

    expect(await within(others()).findByText("Esa plataforma no tiene juegos en Gameómetro ahora mismo."))
      .toBeVisible();
    expect(within(others()).getByRole("link", { name: "Ver todas las plataformas" })).toHaveAttribute("href", "/plataformas");
    expect(within(others()).queryByRole("list", { name: /^Destinos de/ })).not.toBeInTheDocument();
  });

  it("keeps a featured platform named in the URL on its own card", async () => {
    stubPlatforms(() => Response.json({ items: directory }));
    renderApp("/plataformas?plataforma=windows-pc");

    await within(others()).findByRole("list");
    expect(within(others()).queryByRole("list", { name: /^Destinos de/ })).not.toBeInTheDocument();
    expect(within(others()).queryByText(/no tiene juegos/)).not.toBeInTheDocument();
  });

  it("says so when no other platform has games", async () => {
    stubPlatforms(() => Response.json({ items: directory.filter((platform) => platform.platformId.startsWith("1000")) }));
    renderApp("/plataformas");

    expect(await within(others()).findByText(
      "De momento, Gameómetro no tiene juegos de otras plataformas.",
    )).toBeVisible();
  });

  it("keeps the featured platforms through a catalogue not ready and a failed read, with a retry", async () => {
    const user = userEvent.setup();
    const fetchMock = stubPlatforms(problem(503, "CATALOGUE_NOT_READY"));
    renderApp("/plataformas");

    const notReady = await within(others()).findByRole("alert");
    expect(notReady).toHaveTextContent("Las plataformas todavía no están disponibles");
    expect(within(featuredList()).getAllByRole("article")).toHaveLength(4);
    expect(screen.queryByText("Aún sin juegos en Gameómetro")).not.toBeInTheDocument();

    fetchMock.mockImplementation(async (input) => {
      const url = input instanceof Request ? input.url : String(input);
      return url.includes("/platforms") ? problem(500, "INTERNAL_ERROR")() : Response.json({ authenticated: false });
    });
    await user.click(within(notReady).getByRole("button", { name: "Reintentar" }));
    const failure = await within(others()).findByText("No se pudieron cargar las plataformas");
    const alert = failure.closest("[role=alert]") as HTMLElement;
    expect(alert).toHaveTextContent("Referencia para soporte: corr-123");

    fetchMock.mockImplementation(async (input) => {
      const url = input instanceof Request ? input.url : String(input);
      return url.includes("/platforms") ? Response.json({ items: directory }) : Response.json({ authenticated: false });
    });
    await user.click(within(alert).getByRole("button", { name: "Reintentar" }));
    expect(await within(others()).findByRole("button", { name: "Más destinos de Android" })).toBeInTheDocument();
  });
});

describe("PlatformDestinations", () => {
  it("links each destination once it supports the platform, keeping the others unlinked", () => {
    const pc: PlatformContext = {
      key: "windows-pc", name: "Windows PC", platformIds: ["10000000-0000-4000-8000-000000000003"], logo: "windows",
    };
    // Every destination is live today; one without a path for the platform still reads
    // Próximamente and is never a link.
    const destinations = [
      ...platformDestinations.filter(destination => destination.key !== "upcoming"),
      { key: "upcoming" as const, label: "Pendiente", to: () => null },
    ];
    const router = createMemoryRouter([{
      path: "/",
      element: <PlatformDestinations context={pc} destinations={destinations} />,
    }]);
    render(<RouterProvider router={router} />);

    const list = screen.getByRole("list", { name: "Destinos de Windows PC" });
    expect(within(list).getByRole("link", { name: "TOPómetro" })).toHaveAttribute("href", "/plataformas/windows-pc/topometro");
    expect(within(list).getByRole("link", { name: "Explorar catálogo" })).toHaveAttribute("href", "/plataformas/windows-pc/catalogo");
    expect(within(list).getAllByRole("link")).toHaveLength(2);
    expect(within(list).getAllByText("Próximamente")).toHaveLength(1);
  });

  it("leads every platform to its upcoming highlights", () => {
    const upcoming = platformDestinations.find((destination) => destination.key === "upcoming");
    expect(upcoming?.label).toBe("Próximos destacados");
    expect(upcoming?.to({ key: "ps4-id", name: "PlayStation 4", platformIds: ["ps4-id"], logo: "playstation" }))
      .toBe("/plataformas/ps4-id/proximos");
  });
});
