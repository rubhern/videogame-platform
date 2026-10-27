import { QueryClient } from "@tanstack/react-query";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";

import { AppProviders } from "./app-providers";
import { NavigationMenu } from "./navigation-menu";
import { createInterestsSwitch } from "../features/platform-preferences/interests-switch";
import { renderApp } from "../test/render-app";

afterEach(() => vi.unstubAllGlobals());

function stubSession(session: object) {
  vi.stubGlobal("fetch", vi.fn(async () => Response.json(session)));
}

function renderMenu(path: string) {
  const router = createMemoryRouter(
    [{ path: "*", element: <><NavigationMenu /><h1>Outside</h1><button>Next control</button></> }],
    { initialEntries: [path] },
  );
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <AppProviders interests={createInterestsSwitch()} queryClient={queryClient}>
      <RouterProvider router={router} />
    </AppProviders>,
  );
  return { router, trigger: screen.getByRole("button", { name: "Menú" }) };
}

describe("compact navigation menu", () => {
  it("opens the primary sections, then the anonymous account entry apart from them", async () => {
    stubSession({ authenticated: false });
    const user = userEvent.setup();
    const { trigger } = renderMenu("/lanzamientos/calendario?month=2026-08");
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("navigation", { name: "Principal" })).not.toBeInTheDocument();

    await user.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    const panel = document.getElementById(trigger.getAttribute("aria-controls") ?? "");
    expect(panel).not.toBeNull();
    const nav = screen.getByRole("navigation", { name: "Principal" });
    expect(panel).toContainElement(nav);
    const links = within(nav).getAllByRole("link");
    expect(links.map(link => link.textContent)).toEqual([
      "Destacados", "Explorar", "Calendario", "TOPómetro", "Plataformas",
    ]);
    expect(links.map(link => link.getAttribute("href"))).toEqual([
      "/lanzamientos/destacados", "/lanzamientos", "/lanzamientos/calendario", "/topometro", "/plataformas",
    ]);
    expect(within(nav).getByRole("list", { name: "Lanzamientos" })).toBeInTheDocument();
    expect(within(nav).getByRole("link", { name: "Calendario" })).toHaveAttribute("aria-current", "page");
    expect(links.filter(link => link.hasAttribute("aria-current"))).toHaveLength(1);

    const account = await screen.findByRole("group", { name: "Tu cuenta" });
    expect(nav).not.toContainElement(account);
    expect(within(account).getByRole("link", { name: "Iniciar sesión" }))
      .toHaveAttribute("href", "/auth/start?returnTo=%2Flanzamientos%2Fcalendario%3Fmonth%3D2026-08");
    expect(within(account).getByRole("link", { name: "Crear cuenta" })).toHaveAttribute("href", expect.stringContaining("intent=register"));
  });

  it("closes on Escape with focus return, when focus leaves it and on an outside press", async () => {
    stubSession({ authenticated: false });
    const user = userEvent.setup();
    const { trigger } = renderMenu("/not-implemented");
    trigger.focus();
    await user.keyboard("{Enter}{Tab}");
    expect(screen.getByRole("link", { name: "Destacados" })).toHaveFocus();
    await user.keyboard("{Escape}");
    expect(trigger).toHaveFocus();
    expect(trigger).toHaveAttribute("aria-expanded", "false");

    await user.keyboard(" ");
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    await screen.findByRole("link", { name: "Crear cuenta" });
    // Five sections and both account links, then focus leaves for the page.
    await user.keyboard("{Tab}{Tab}{Tab}{Tab}{Tab}{Tab}{Tab}{Tab}");
    expect(screen.getByRole("button", { name: "Next control" })).toHaveFocus();
    expect(trigger).toHaveAttribute("aria-expanded", "false");

    await user.click(trigger);
    await user.click(screen.getByRole("heading", { name: "Outside" }));
    expect(trigger).toHaveAttribute("aria-expanded", "false");

    await user.click(trigger);
    await user.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "false");
  });

  it("closes after following a link, returning focus for the page already shown, but not on a query change", async () => {
    stubSession({ authenticated: false });
    const user = userEvent.setup();
    const { router, trigger } = renderMenu("/lanzamientos?sort=hype&page=2");
    await user.click(trigger);
    const explore = screen.getByRole("link", { name: "Explorar" });
    expect(explore).toHaveAttribute("href", "/lanzamientos?sort=hype");
    await user.click(explore);
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(trigger).toHaveFocus();

    await user.click(trigger);
    await act(() => router.navigate("/lanzamientos?sort=date", { replace: true }));
    expect(trigger).toHaveAttribute("aria-expanded", "true");

    await user.click(screen.getByRole("link", { name: "Plataformas" }));
    expect(router.state.location.pathname).toBe("/plataformas");
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    await user.click(trigger);
    expect(screen.getByRole("link", { name: "Plataformas" })).toHaveAttribute("aria-current", "page");
    await act(() => router.navigate(-1));
    expect(trigger).toHaveAttribute("aria-expanded", "false");
  });

  it("carries the authenticated account actions, administration only with its capability", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const request = input instanceof Request ? input : new Request(input);
      return request.method === "POST"
        ? new Response(null, { status: 403 })
        : Response.json({ authenticated: true, csrfToken: "menu-csrf", capabilities: ["catalogue_administration"] });
    });
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();
    const { trigger } = renderMenu("/mis-puntuaciones");
    await user.click(trigger);

    const account = await screen.findByRole("group", { name: "Mi cuenta" });
    expect(within(account).getAllByRole("link").map(link => link.textContent)).toEqual([
      "Mis puntuaciones", "Tus estadísticas", "Mis plataformas", "Panel de administrador",
    ]);
    expect(within(account).queryByRole("link", { name: "Iniciar sesión" })).not.toBeInTheDocument();

    // The page already shown: the menu closes and focus returns to its button.
    await user.click(within(account).getByRole("link", { name: "Mis puntuaciones" }));
    expect(trigger).toHaveFocus();
    expect(trigger).toHaveAttribute("aria-expanded", "false");

    await user.click(trigger);
    await user.click(within(screen.getByRole("group", { name: "Mi cuenta" })).getByRole("button", { name: "Cerrar sesión" }));
    const logout = fetchMock.mock.calls.map(call => call[0])
      .find((input): input is Request => input instanceof Request && input.method === "POST");
    expect(logout?.headers.get("X-CSRF-Token")).toBe("menu-csrf");
    expect(await screen.findByRole("alert")).toHaveTextContent("No se pudo cerrar la sesión");
  });
});

describe("compact header", () => {
  it("replaces the visible sections and account control with the menu below tablet width", async () => {
    stubSession({ authenticated: false });
    vi.stubGlobal("matchMedia", (query: string) => ({
      matches: query === "(max-width: 719px)",
      media: query,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    }));
    renderApp("/not-implemented");
    const banner = screen.getByRole("banner");
    await waitFor(() => expect(within(banner).getByRole("button", { name: "Menú" })).toBeInTheDocument());
    expect(within(banner).queryByRole("navigation", { name: "Principal" })).not.toBeInTheDocument();
    expect(within(banner).queryByRole("button", { name: "Lanzamientos" })).not.toBeInTheDocument();
    expect(within(banner).queryByRole("link", { name: "Iniciar sesión" })).not.toBeInTheDocument();
    expect(within(banner).getByRole("link", { name: "Gameómetro · Inicio" })).toBeInTheDocument();
    expect(within(banner).getAllByRole("search").length).toBeGreaterThan(0);
  });
});
