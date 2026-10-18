import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { renderApp } from "../test/render-app";

type Session = "anonymous" | "player" | "operator";

const counters = {
  createdGames: 3, updatedGames: 41, unchangedGames: 512, deferredGames: 0, failedGames: 2,
  createdReleases: 7, updatedReleases: 55, unchangedReleases: 1290, deletedReleases: 1,
  providerRequests: 38, providerRetries: 1,
};
const status = {
  currentRun: {
    runId: "run-now", from: "2026-09-20", to: "2026-11-15", startedAt: "2026-10-18T04:00:00Z",
    lastHeartbeatAt: "2026-10-18T04:03:00Z", stalled: false,
  },
  recentRuns: [
    { runId: "run-1", from: "1997-12-01", to: "1997-12-31", startedAt: "2026-10-17T04:00:00Z",
      completedAt: "2026-10-17T04:06:41Z", result: "partial", outcomeCode: "SYNCHRONIZATION_INTERRUPTED", counters },
    { runId: "run-2", from: "1998-01-01", to: "1998-01-31", startedAt: "2026-10-16T04:00:00Z",
      completedAt: "2026-10-16T04:45:00Z", result: "abandoned", outcomeCode: "SYNCHRONIZATION_ABANDONED" },
  ],
};
const scan = (completedAt: string, failedGames = 0) => ({ runId: "run-1", scanStartedAt: completedAt, scanCompletedAt: completedAt, failedGames });

function coverage(from: string, to: string) {
  return {
    from, to,
    periods: [
      { from, to: "1997-11-30", status: "never_covered" },
      { from: "1997-12-01", to: "1997-12-15", status: "covered", lastScan: scan(new Date(Date.now() - 86_400_000).toISOString(), 2) },
      { from: "1997-12-16", to: "1997-12-31", status: "covered", lastScan: scan("2025-01-10T04:00:00Z") },
      { from: "1998-01-01", to, status: "never_covered" },
    ],
  };
}

function stub(session: Session, handler?: (url: URL) => Response) {
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const url = new URL((input instanceof Request ? input : new Request(input)).url);
    if (url.pathname === "/api/v1/session") {
      return Response.json(session === "anonymous" ? { authenticated: false }
        : { authenticated: true, csrfToken: "csrf", ...(session === "operator" ? { capabilities: ["catalogue_administration"] } : {}) });
    }
    if (handler) return handler(url);
    if (session !== "operator") {
      return Response.json({ code: "ACCESS_DENIED", correlationId: "corr-403" },
        { status: 403, headers: { "Content-Type": "application/problem+json" } });
    }
    if (url.pathname === "/api/v1/admin/catalogue/synchronization") return Response.json(status);
    return Response.json(coverage(url.searchParams.get("from") ?? "", url.searchParams.get("to") ?? ""));
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

const adminReads = (fetchMock: ReturnType<typeof stub>) => fetchMock.mock.calls
  .map(([request]) => new URL((request as Request).url))
  .filter((url) => url.pathname.startsWith("/api/v1/admin/"));

afterEach(() => vi.unstubAllGlobals());

describe("Catalogue administration", () => {
  it("asks an anonymous visitor to sign in without reading administration data", async () => {
    const fetchMock = stub("anonymous");
    renderApp("/administracion/catalogo");

    const heading = await screen.findByRole("heading", { name: "Necesitas una sesión activa" });
    expect(within(heading.closest(".notice") as HTMLElement).getByRole("link", { name: "Iniciar sesión" }))
      .toHaveAttribute("href", expect.stringContaining("returnTo=%2Fadministracion%2Fcatalogo"));
    expect(adminReads(fetchMock)).toHaveLength(0);
  });

  it("explains a forbidden account without exposing any evidence", async () => {
    const fetchMock = stub("player");
    renderApp("/administracion/catalogo");

    expect(await screen.findByRole("heading", { name: "Tu cuenta no puede administrar el catálogo" })).toBeVisible();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(adminReads(fetchMock).map((url) => url.pathname)).toEqual(["/api/v1/admin/catalogue/synchronization"]);
  });

  it("shows the run in flight and recent runs whose failures never claim coverage", async () => {
    stub("operator");
    renderApp("/administracion/catalogo");

    const current = await screen.findByRole("region", { name: "Ejecución actual" });
    expect(within(current).getByText("En curso")).toBeVisible();
    const [interrupted, abandoned] = screen.getAllByRole("article") as [HTMLElement, HTMLElement];
    expect(within(interrupted).getByRole("heading", { name: /^Interrumpida/ })).toBeVisible();
    expect(interrupted).toHaveTextContent("no registra cobertura");
    expect(interrupted).toHaveTextContent("2 fallidos");
    expect(interrupted).toHaveTextContent("SYNCHRONIZATION_INTERRUPTED");
    expect(within(abandoned).getByRole("heading", { name: /^Abandonada/ })).toBeVisible();
    expect(abandoned).toHaveTextContent("Sin contadores");
  });

  it("reads one decade, summarizes its years and months in words, and drills into a month by keyboard", async () => {
    const fetchMock = stub("operator");
    const { router } = renderApp("/administracion/catalogo?tab=cobertura&year=1997");

    const years = await screen.findByRole("table", { name: "Cobertura por año, 1990–1999" });
    const year1997 = within(years).getByRole("row", { name: /^1997/ });
    expect(year1997).toHaveTextContent("Parcialmente cubierto");
    expect(within(years).getByRole("link", { name: "1997" })).toHaveAttribute("aria-current", "true");
    expect(within(years).getByRole("row", { name: /^1995/ })).toHaveTextContent("Nunca cubierto");
    // One decade read answers the year too: no unbounded or repeated requests.
    expect(adminReads(fetchMock).filter((url) => url.pathname.endsWith("coverage")).map((url) => url.search))
      .toEqual(["?from=1990-01-01&to=1999-12-31"]);

    const months = screen.getByRole("table", { name: "Cobertura por mes de 1997" });
    const december = within(months).getByRole("row", { name: /^Diciembre/ });
    expect(december).toHaveTextContent("Cubierto");
    expect(december).toHaveTextContent("31 de 31 días");
    expect(december).toHaveTextContent(/Hace \d+ días/);
    expect(december).toHaveTextContent("Con juegos fallidos");
    expect(within(months).getByRole("row", { name: /^Noviembre/ })).toHaveTextContent("Nunca cubierto");

    within(december).getByRole("link", { name: "Diciembre" }).focus();
    await userEvent.keyboard("{Enter}");

    await waitFor(() => expect(router.state.location.search).toBe("?tab=cobertura&year=1997&month=12"));
    const detail = screen.getByRole("heading", { name: "Periodos de diciembre de 1997" });
    await waitFor(() => expect(detail).toHaveFocus());
    const periods = within(screen.getByRole("region", { name: "Periodos de diciembre de 1997" })).getAllByRole("listitem");
    expect(periods).toHaveLength(2);
    expect(periods[0]).toHaveTextContent("Cubierto");
    expect(periods[0]).toHaveTextContent("su ejecución tuvo 2 juegos fallidos");
  });

  it("moves across decades with bounded ranges", async () => {
    const fetchMock = stub("operator");
    renderApp("/administracion/catalogo?tab=cobertura&year=1997");

    await userEvent.click(await screen.findByRole("link", { name: "2000–2009" }));

    expect(await screen.findByRole("table", { name: "Cobertura por año, 2000–2009" })).toBeVisible();
    expect(adminReads(fetchMock).map((url) => url.search)).toContain("?from=2000-01-01&to=2009-12-31");
  });

  it("reads a year separately when the decade answer stopped before it", async () => {
    const fetchMock = stub("operator", (url) => {
      if (url.pathname.endsWith("/synchronization")) return Response.json({ recentRuns: [] });
      const from = url.searchParams.get("from") ?? "";
      const to = url.searchParams.get("to") ?? "";
      return Response.json(from === "1990-01-01"
        ? { from, to, continuesFrom: "1995-01-01", periods: [{ from, to: "1994-12-31", status: "never_covered" }] }
        : coverage(from, to));
    });
    const { router } = renderApp("/administracion/catalogo?tab=cobertura&year=1997");

    const years = await screen.findByRole("table", { name: "Cobertura por año, 1990–1999" });
    expect(within(years).getByRole("row", { name: /^1997/ })).toHaveTextContent("Sin describir");
    expect(screen.getByText(/el resumen se detiene el/)).toBeVisible();
    expect(await screen.findByRole("table", { name: "Cobertura por mes de 1997" })).toBeVisible();
    expect(adminReads(fetchMock).map((url) => url.search)).toContain("?from=1997-01-01&to=1997-12-31");
    await router.navigate("/administracion/catalogo");
    expect(await screen.findByText("No hay ninguna sincronización en curso.")).toBeVisible();
    expect(screen.getByRole("heading", { name: "Todavía no hay ejecuciones terminadas" })).toBeVisible();
  });

  it("splits runs and coverage into keyboard-operable tabs kept in the URL", async () => {
    const fetchMock = stub("operator");
    const { router } = renderApp("/administracion/catalogo");

    const runsTab = await screen.findByRole("tab", { name: "Ejecuciones" });
    const coverageTab = screen.getByRole("tab", { name: "Cobertura" });
    expect(runsTab).toHaveAttribute("aria-selected", "true");
    expect(coverageTab).toHaveAttribute("tabindex", "-1");
    expect(screen.getByRole("tabpanel", { name: "Ejecuciones" })).toContainElement(
      screen.getByRole("heading", { name: "Ejecución actual" }));
    // Coverage is read only once its tab is open.
    expect(adminReads(fetchMock).some((url) => url.pathname.endsWith("coverage"))).toBe(false);

    runsTab.focus();
    await userEvent.keyboard("{ArrowRight}");

    expect(coverageTab).toHaveFocus();
    expect(coverageTab).toHaveAttribute("aria-selected", "true");
    await waitFor(() => expect(router.state.location.search).toBe("?tab=cobertura"));
    const panel = screen.getByRole("tabpanel", { name: "Cobertura" });
    expect(await within(panel).findByRole("table", { name: /^Cobertura por año/ })).toBeVisible();
    expect(screen.queryByRole("heading", { name: "Ejecución actual" })).not.toBeInTheDocument();

    await userEvent.keyboard("{Home}");
    expect(runsTab).toHaveFocus();
    await waitFor(() => expect(router.state.location.search).toBe("?tab=ejecuciones"));
  });

  it("keeps a technical failure safe and recoverable", async () => {
    let fail = true;
    stub("operator", (url) => {
      if (fail) {
        return Response.json({ code: "INTERNAL_ERROR", correlationId: "corr-500", detail: "SQL state 42P01" },
          { status: 500, headers: { "Content-Type": "application/problem+json" } });
      }
      return url.pathname.endsWith("/synchronization") ? Response.json(status) : Response.json(coverage("1990-01-01", "1999-12-31"));
    });
    renderApp("/administracion/catalogo?year=1997");

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("No se pudo leer el estado de la sincronización");
    expect(alert).toHaveTextContent("corr-500");
    expect(alert).not.toHaveTextContent("42P01");
    fail = false;
    await userEvent.click(within(alert).getByRole("button", { name: "Reintentar" }));
    expect(await screen.findByRole("region", { name: "Ejecución actual" })).toBeVisible();
  });
});
