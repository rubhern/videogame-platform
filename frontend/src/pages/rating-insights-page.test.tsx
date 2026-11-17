import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { RatingInsightsPage } from "./rating-insights-page";
import { SESSION_QUERY_KEY } from "../features/session/use-session";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { renderApp } from "../test/render-app";
import { emptyRatingInsightsFixture, ratingInsightsFixture } from "../test/fixtures/rating-insights";

const path = "/mis-puntuaciones/estadisticas";
function stub(reply: (request: Request) => Response | Promise<Response>, authenticated = true) {
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async input => {
    const request = input instanceof Request ? input : new Request(input);
    if (new URL(request.url).pathname === "/api/v1/session") return Response.json(
      authenticated ? { authenticated: true, csrfToken: "session-a" } : { authenticated: false });
    return reply(request);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}
afterEach(() => vi.unstubAllGlobals());

describe("Tus estadísticas", () => {
  it("shows only personal results, exposes all buckets by keyboard and switches genre samples", async () => {
    const data = ratingInsightsFixture();
    const fetch = stub(request => {
      expect(request.cache).toBe("no-store");
      return Response.json(data);
    });
    renderApp(path);
    await screen.findByRole("heading", { name: "Así puntúas" });
    expect(screen.getByText("84")).toBeVisible();
    expect(screen.getAllByText("7,2")[0]).toBeVisible();
    expect(screen.queryByText(/Jugadores IGDB|Puntuación de la comunidad/)).not.toBeInTheDocument();
    const histogram = screen.getByRole("group", { name: "Distribución de tus puntuaciones" });
    expect(within(histogram).getAllByRole("button")).toHaveLength(10);
    const nine = within(histogram).getByRole("button", { name: /^9 de 10: 18 juegos/ });
    nine.focus();
    await userEvent.keyboard("{Enter}");
    expect(nine).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText("9 de 10 · Ardiendo · 18 juegos · 21,4 % de tu colección")).toBeVisible();
    await userEvent.click(screen.getByRole("button", { name: "Mejor puntuados" }));
    const genres=screen.getByRole("region", { name: "Tu mapa de géneros" });
    expect(within(genres).getAllByRole("listitem")[0]).toHaveTextContent("Rol");
    expect(within(genres).getAllByRole("listitem")[0]).toHaveTextContent("29 juegos");
    await userEvent.click(screen.getByText("Ver meses y recuentos"));
    expect(screen.getByRole("table")).toHaveTextContent("diciembre de 2025");
    const high = screen.getByRole("article", { name: "Tu puntuación más alta" });
    expect(within(high).getByRole("link")).toHaveAttribute("href", "/games/disco-elysium/disco-elysium");
    expect(high).toHaveTextContent("8 juegos comparten esta puntuación");
    expect(fetch.mock.calls.filter(([r]) => (r as Request).url.includes("rating-insights"))).toHaveLength(1);
  });

  it("reads recorded platforms as overlapping counts with means only from platform scores", async () => {
    stub(() => Response.json(ratingInsightsFixture()));
    renderApp(path);
    const section = await screen.findByRole("region", { name: "Dónde has jugado" });
    expect(section).toHaveTextContent("73 selecciones en total");
    expect(section).toHaveTextContent("22 juegos sin plataforma indicada; no entran en el gráfico");
    const legend = within(section).getAllByRole("listitem").filter(row => row.closest(".insights-donut-legend"));
    expect(legend.map(row => row.textContent?.replace(/\s/g, " "))).toEqual([
      "PS5 31 42,5 %", "Switch 2 9 12,3 %", "Xbox Series X|S 4 5,5 %", "PC Windows 18 24,6 %", "Otras 11 15,1 %"]);
    await userEvent.click(within(section).getByText("Tus puntuaciones por plataforma"));
    const rows = within(section).getAllByRole("listitem").filter(row => row.closest("ol"));
    expect(rows).toHaveLength(6);
    expect(rows[0]).toHaveTextContent("PlayStation 5");
    expect(rows[0]).toHaveTextContent("31 juegos, 14 con puntuación");
    expect(rows[0]).toHaveTextContent("Tu media en PlayStation 5: 7,9 /10 Caliente");
    // Played without a single platform score: no mean, no temperature.
    expect(rows[4]).toHaveTextContent("Xbox Series X|S4 juegos, ninguno con puntuaciónSin puntuar");
    expect(rows[4]).not.toHaveTextContent(/\/10|Congelado|Frío|Templado|Caliente|Ardiendo/);
    expect(section).toHaveTextContent("Y 2 plataformas más fuera de esta lista.");
    expect(within(section).queryByRole("link")).not.toBeInTheDocument();
  });

  it("states missing platform information without inventing one or drawing empty bars", async () => {
    const data = ratingInsightsFixture();
    stub(() => Response.json({ ...data, platforms: { items: [], otherPlatforms: 0, gamesWithoutPlatforms: 84, gamesOnSeveralPlatforms: 0, distribution: { ps5: 0, switch2: 0, xboxSeries: 0, windowsPc: 0, other: 0 } } }));
    renderApp(path);
    const section = await screen.findByRole("region", { name: "Dónde has jugado" });
    expect(section).toHaveTextContent("Aún no has indicado en qué plataformas jugaste tus 84 juegos.");
    expect(within(section).getAllByRole("listitem")).toHaveLength(5);
    expect(section.querySelectorAll(".insights-donut-slice")).toHaveLength(0);
    expect(section).toHaveTextContent("0 selecciones en total");
    expect(within(section).getByRole("link", { name: "Ir a Mis puntuaciones" })).toHaveAttribute("href", "/mis-puntuaciones");
    // The rest of the page keeps its readings.
    expect(screen.getByRole("heading", { name: "Así puntúas" })).toBeVisible();
  });

  it("explains a played-only collection and a bucket toggled off again", async () => {
    const data = ratingInsightsFixture();
    const played = data.platforms.items.map(item => ({ ...item, scoredGames: 0, mean: null }));
    stub(() => Response.json({ ...data, platforms: { ...data.platforms, items: played, otherPlatforms: 0 } }));
    renderApp(path);
    const section = await screen.findByRole("region", { name: "Dónde has jugado" });
    expect(section).toHaveTextContent("Todavía no has puntuado ningún juego por plataforma");
    await userEvent.click(within(section).getByText("Tus puntuaciones por plataforma"));
    expect(within(section).getAllByText("Sin puntuar")).toHaveLength(6);
    expect(section).not.toHaveTextContent("fuera de esta lista");
    const readout = screen.getByText(/La aguja marca tu media exacta, 7,2/);
    const ten = screen.getByRole("button", { name: /^10 de 10: 8 juegos/ });
    await userEvent.click(ten);
    expect(ten).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(ten);
    expect(ten).toHaveAttribute("aria-pressed", "false");
    expect(readout).toHaveTextContent("Cada columna reúne los juegos cuya puntuación se redondea a ese número.");
  });

  it("keeps an empty collection free of charts and offers discovery and the personal list", async () => {
    stub(() => Response.json(emptyRatingInsightsFixture()));
    renderApp(path);
    await screen.findByRole("heading", { name: "Tu historia empieza con una puntuación" });
    expect(screen.getByRole("link", { name: "Buscar juegos" })).toHaveAttribute("href", "/search");
    expect(screen.queryByRole("heading", { name: "Así puntúas" })).not.toBeInTheDocument();
    expect(screen.queryByText("7,2")).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Dónde has jugado" })).not.toBeInTheDocument();
  });

  it("labels small samples, coincident extremes and missing metadata without preferences", async () => {
    const data=emptyRatingInsightsFixture();
    const source=ratingInsightsFixture().highest;
    const extreme=source ? {...source,tiedGames:1} : null;
    stub(() => Response.json({ ...data, total: 1, mean: 10, distribution: [0,0,0,0,0,0,0,0,0,1],
      highest: extreme, lowest: extreme, gamesWithoutGenres: 1 }));
    renderApp(path);
    await screen.findByText(/Con menos de cinco juegos/);
    expect(screen.getByText("Todas tus puntuaciones coinciden.")).toBeVisible();
    expect(screen.getByText(/1 sin género en el catálogo/)).toBeVisible();
    await userEvent.click(screen.getByRole("button", { name: "Mejor puntuados" }));
    expect(screen.getByText(/Aún no hay géneros con tres juegos/)).toBeVisible();
  });

  it("waits for authentication, never requests anonymous statistics and preserves the login target", async () => {
    const fetch=stub(() => Response.json({}),false);
    renderApp(path);
    const region=screen.getByRole("region", { name: "Tus estadísticas" });
    const link=await within(region).findByRole("link", { name: "Iniciar sesión" });
    expect(link).toHaveAttribute("href", "/auth/start?returnTo=%2Fmis-puntuaciones%2Festadisticas");
    expect(fetch.mock.calls.some(([r]) => (r as Request).url.includes("rating-insights"))).toBe(false);
  });

  it("retries a failed read, preserving a safe support reference and without invented charts", async () => {
    let fail=true;
    stub(() => fail ? Response.json({ code: "PERSONAL_RATINGS_READ_FAILED", correlationId: "support-254" }, { status: 500 }) : Response.json(ratingInsightsFixture()));
    renderApp(path);
    await screen.findByRole("heading", { name: "No se pudieron cargar tus estadísticas" });
    expect(screen.getByText("Referencia: support-254")).toBeVisible();
    expect(screen.queryByRole("heading", { name: "Así puntúas" })).not.toBeInTheDocument();
    fail=false;
    await userEvent.click(screen.getByRole("button", { name: "Reintentar carga" }));
    await screen.findByRole("heading", { name: "Así puntúas" });
  });

  it("does not show account A while account B's private response is pending in the same mounted page", async () => {
    let resolve: ((value: Response) => void) | undefined;
    let reads=0;
    stub(() => ++reads===1 ? Response.json(ratingInsightsFixture()) : new Promise<Response>(done => { resolve=done; }));
    const client=new QueryClient({defaultOptions:{queries:{retry:false}}});
    const router=createMemoryRouter([{path:"*",element:<RatingInsightsPage />}]);
    render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
    await screen.findByText("84");
    act(() => {client.setQueryData(SESSION_QUERY_KEY,{authenticated:true,csrfToken:"session-b",capabilities:[]});});
    await waitFor(() => expect(screen.queryByText("84")).not.toBeInTheDocument());
    await waitFor(() => expect(resolve).toBeDefined());
    await act(async () => { resolve?.(Response.json(emptyRatingInsightsFixture())); });
    await screen.findByRole("heading", { name: "Tu historia empieza con una puntuación" });
    act(() => {client.setQueryData(SESSION_QUERY_KEY,{authenticated:false});});
    await screen.findByRole("heading", {name:"Necesitas una sesión activa"});
    expect(screen.queryByText("84")).not.toBeInTheDocument();
  });

  it("treats a statistics 401 as a lost session instead of showing a technical chart error", async () => {
    stub(() => Response.json({code:"AUTHENTICATION_REQUIRED"},{status:401}));
    renderApp(path);
    await screen.findByRole("heading",{name:"Necesitas una sesión activa"});
    expect(screen.queryByRole("heading",{name:"No se pudieron cargar tus estadísticas"})).not.toBeInTheDocument();
  });
});
