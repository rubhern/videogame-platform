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

  it("keeps an empty collection free of charts and offers discovery and the personal list", async () => {
    stub(() => Response.json(emptyRatingInsightsFixture()));
    renderApp(path);
    await screen.findByRole("heading", { name: "Tu historia empieza con una puntuación" });
    expect(screen.getByRole("link", { name: "Buscar juegos" })).toHaveAttribute("href", "/search");
    expect(screen.queryByRole("heading", { name: "Así puntúas" })).not.toBeInTheDocument();
    expect(screen.queryByText("7,2")).not.toBeInTheDocument();
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
