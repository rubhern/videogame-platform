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

describe("Catalogue synchronization request", () => {
  const accepted = { runId: "run-new", from: "2026-10-01", to: "2026-12-31", startedAt: "2026-10-18T10:15:30Z" };
  const running = {
    runId: "run-new", from: "2026-10-01", to: "2026-12-31", startedAt: "2026-10-18T10:15:30Z",
    lastHeartbeatAt: "2026-10-18T10:15:40Z", stalled: false,
  };
  const finished = {
    runId: "run-new", from: "2026-10-01", to: "2026-12-31", startedAt: "2026-10-18T10:15:30Z",
    completedAt: "2026-10-18T10:20:00Z", result: "partial", outcomeCode: "SYNCHRONIZATION_COMPLETED_WITH_FAILURES", counters,
  };
  const problem = (status: number, code: string, pointer?: string) => Response.json(
    { code, correlationId: `corr-${status}`, ...(pointer ? { violations: [{ pointer, code, message: "m" }] } : {}) },
    { status, headers: { "Content-Type": "application/problem+json" } });

  /** Every session read, status read and request, with what each request carried. */
  function stubRequests(answer: () => Response, statuses: unknown[] = [{ recentRuns: [] }]) {
    const posts: { csrf: string | null; body: unknown }[] = [];
    let sessionReads = 0;
    let statusReads = 0;
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input, init) => {
      const request = input instanceof Request ? input : new Request(input, init);
      const url = new URL(request.url);
      if (url.pathname === "/api/v1/session") {
        sessionReads++;
        return Response.json({ authenticated: true, csrfToken: "csrf", capabilities: ["catalogue_administration"] });
      }
      if (request.method === "POST") {
        posts.push({ csrf: request.headers.get("X-CSRF-Token"), body: await request.json() });
        return answer();
      }
      const next = statuses[Math.min(statusReads, statuses.length - 1)];
      statusReads++;
      return Response.json(next);
    });
    vi.stubGlobal("fetch", fetchMock);
    return { posts, sessionReads: () => sessionReads };
  }

  async function requestInterval(from: string, to: string) {
    await userEvent.type(await screen.findByLabelText("Desde"), from);
    await userEvent.type(screen.getByLabelText("Hasta"), to);
    await userEvent.click(screen.getByRole("button", { name: "Solicitar sincronización" }));
  }

  it("accepts an interval with CSRF proof and follows the run through the status until its result", async () => {
    const { posts } = stubRequests(() => Response.json(accepted, { status: 202 }),
      [{ recentRuns: [] }, { currentRun: running, recentRuns: [] }, { recentRuns: [finished] }]);
    renderApp("/administracion/catalogo");

    await requestInterval("2026-10-01", "2026-12-31");

    expect(posts).toEqual([{ csrf: "csrf", body: { from: "2026-10-01", to: "2026-12-31" } }]);
    const feedback = await screen.findByRole("heading", { name: "Sincronización en curso" });
    const notice = feedback.closest("[role=status]") as HTMLElement;
    await waitFor(() => expect(notice).toHaveFocus());
    expect(notice).toHaveTextContent("Solicitud aceptada");
    // A run in flight blocks a second request until it finishes.
    expect(screen.getByRole("button", { name: "Solicitar sincronización" })).toBeDisabled();

    await userEvent.click(screen.getByRole("button", { name: "Actualizar estado" }));

    expect(await within(notice).findByRole("heading", { name: "Completa con juegos fallidos" })).toBeVisible();
    expect(notice).toHaveTextContent("los juegos fallidos conservan su último estado válido");
    expect(screen.getByRole("button", { name: "Solicitar sincronización" })).toBeEnabled();
  });

  it("explains an invalid interval beside its field without sending anything", async () => {
    const { posts } = stubRequests(() => Response.json(accepted, { status: 202 }));
    renderApp("/administracion/catalogo");

    await requestInterval("2026-12-31", "2026-10-01");

    const to = screen.getByLabelText("Hasta");
    expect(to).toHaveAttribute("aria-invalid", "true");
    expect(to).toHaveAccessibleDescription("No puede ser anterior a la fecha de inicio.");
    expect(to).toHaveFocus();

    await userEvent.clear(screen.getByLabelText("Desde"));
    await userEvent.type(screen.getByLabelText("Desde"), "2026-01-01");
    await userEvent.clear(to);
    await userEvent.type(to, "2026-07-04");
    await userEvent.click(screen.getByRole("button", { name: "Solicitar sincronización" }));

    expect(to).toHaveAccessibleDescription("El intervalo admite como máximo 184 días.");
    expect(posts).toHaveLength(0);
  });

  it("puts a server-rejected bound on the field the server names", async () => {
    stubRequests(() => problem(422, "SYNCHRONIZATION_RANGE_INVALID", "/from"));
    renderApp("/administracion/catalogo");

    await requestInterval("2026-10-01", "2026-12-31");

    const from = screen.getByLabelText("Desde");
    await waitFor(() => expect(from).toHaveAttribute("aria-invalid", "true"));
    expect(from).toHaveFocus();
  });

  it.each([
    ["SYNCHRONIZATION_ALREADY_RUNNING", 409, "Ya hay una sincronización en curso"],
    ["SYNCHRONIZATION_DISABLED", 422, "La sincronización no está disponible"],
    ["ACCESS_DENIED", 403, "Tu cuenta no puede solicitar sincronizaciones"],
  ])("states that a %s rejection started nothing", async (code, status, heading) => {
    stubRequests(() => problem(status, code));
    renderApp("/administracion/catalogo");

    await requestInterval("2026-10-01", "2026-12-31");

    const notice = (await screen.findByRole("heading", { name: heading })).closest("[role=status]") as HTMLElement;
    await waitFor(() => expect(notice).toHaveFocus());
    expect(notice.querySelector(".notice")).toHaveClass("notice-warning");
  });

  it("renews the session's CSRF proof after a rejected one", async () => {
    const { sessionReads } = stubRequests(() => problem(403, "CSRF_VALIDATION_FAILED"));
    renderApp("/administracion/catalogo");
    await screen.findByLabelText("Desde");
    const before = sessionReads();

    await requestInterval("2026-10-01", "2026-12-31");

    expect(await screen.findByRole("heading", { name: "No se pudo verificar la solicitud" })).toBeVisible();
    await waitFor(() => expect(sessionReads()).toBeGreaterThan(before));
  });

  it("keeps a technical failure safe with its support reference", async () => {
    stubRequests(() => problem(500, "INTERNAL_ERROR"));
    renderApp("/administracion/catalogo");

    await requestInterval("2026-10-01", "2026-12-31");

    const heading = await screen.findByRole("heading", { name: "No se pudo solicitar la sincronización" });
    const notice = heading.closest(".notice") as HTMLElement;
    expect(notice).toHaveClass("notice-danger");
    expect(notice).toHaveTextContent("corr-500");
  });
});

describe("Catalogue synchronization stop and abandonment", () => {
  const running = {
    runId: "run-now", from: "2026-09-20", to: "2026-11-15", startedAt: "2026-10-18T04:00:00Z",
    lastHeartbeatAt: "2026-10-18T04:03:00Z", stalled: false,
  };
  const stopRequested = { ...running, stopRequestedAt: "2026-10-18T04:03:30Z" };
  const stalled = { ...running, stalled: true };
  const finished = (result: string, outcomeCode: string, extra: object = {}) => ({
    runId: "run-now", from: "2026-09-20", to: "2026-11-15", startedAt: "2026-10-18T04:00:00Z",
    completedAt: "2026-10-18T04:10:00Z", result, outcomeCode, ...extra,
  });
  const problem = (status: number, code: string) => Response.json({ code, correlationId: `corr-${status}` },
    { status, headers: { "Content-Type": "application/problem+json" } });

  /** Status reads advance through `statuses`; every command records its path and CSRF proof. */
  function stubCommands(answer: (path: string) => Response, statuses: unknown[]) {
    const commands: { path: string; csrf: string | null; body: string }[] = [];
    let statusReads = 0;
    vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockImplementation(async (input, init) => {
      const request = input instanceof Request ? input : new Request(input, init);
      const url = new URL(request.url);
      if (url.pathname === "/api/v1/session") {
        return Response.json({ authenticated: true, csrfToken: "csrf", capabilities: ["catalogue_administration"] });
      }
      if (request.method === "POST") {
        commands.push({ path: url.pathname, csrf: request.headers.get("X-CSRF-Token"), body: await request.text() });
        return answer(url.pathname);
      }
      const next = statuses[Math.min(statusReads, statuses.length - 1)];
      statusReads++;
      return Response.json(next);
    }));
    return { commands, statusReads: () => statusReads };
  }

  async function current() {
    return screen.findByRole("region", { name: "Ejecución actual" });
  }

  it("confirms a stop, sends it for that run and follows it from requested to stopped", async () => {
    const { commands } = stubCommands(
      () => Response.json({ runId: "run-now", stopRequestedAt: "2026-10-18T04:03:30Z" }, { status: 202 }),
      [{ currentRun: running, recentRuns: [] }, { currentRun: stopRequested, recentRuns: [] },
        { recentRuns: [finished("partial", "SYNCHRONIZATION_STOPPED", { stopRequestedAt: "2026-10-18T04:03:30Z", counters })] }]);
    renderApp("/administracion/catalogo");
    const section = await current();

    const trigger = await within(section).findByRole("button", { name: "Detener sincronización" });
    await userEvent.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    const confirmation = within(section).getByRole("group", { name: /^¿Detener la sincronización/ });
    expect(confirmation).toHaveAccessibleDescription(/no al instante.*no quedará registrado como cubierto/);
    expect(within(confirmation).getByRole("button", { name: "Seguir sincronizando" })).toHaveFocus();
    expect(commands).toHaveLength(0);

    await userEvent.click(within(confirmation).getByRole("button", { name: "Sí, detener" }));

    expect(commands).toEqual([{ path: "/api/v1/admin/catalogue/synchronization-runs/run-now/stop", csrf: "csrf", body: "" }]);
    const accepted = await within(section).findByRole("heading", { name: "Se detendrá en su próximo punto seguro" });
    const notice = accepted.closest("[role=status]") as HTMLElement;
    await waitFor(() => expect(notice).toHaveFocus());
    // The run reads as stop requested, never as stopped, while it is still in flight.
    expect(await within(section).findByText("Detención solicitada", { selector: ".admin-run-result" })).toBeVisible();
    expect(within(section).queryByRole("button", { name: "Detener sincronización" })).not.toBeInTheDocument();

    await userEvent.click(within(section).getByRole("button", { name: "Actualizar estado" }));

    expect(await within(notice).findByRole("heading", { name: "Detenida" })).toBeVisible();
    expect(notice).toHaveTextContent("Detención completada");
    expect(notice).toHaveTextContent("no registra cobertura");
  });

  it("closes a confirmation without sending anything and returns focus to its trigger", async () => {
    const { commands } = stubCommands(() => problem(500, "INTERNAL_ERROR"), [{ currentRun: running, recentRuns: [] }]);
    renderApp("/administracion/catalogo");
    const trigger = await within(await current()).findByRole("button", { name: "Detener sincronización" });

    await userEvent.click(trigger);
    await userEvent.click(screen.getByRole("button", { name: "Seguir sincronizando" }));
    expect(trigger).toHaveFocus();
    expect(trigger).toHaveAttribute("aria-expanded", "false");

    await userEvent.click(trigger);
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("group", { name: /^¿Detener/ })).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
    expect(commands).toHaveLength(0);
  });

  it("offers abandonment only for a stalled run and reports it abandoned once the server fences it", async () => {
    const { commands } = stubCommands(
      () => Response.json(finished("abandoned", "SYNCHRONIZATION_ABANDONED_BY_OPERATOR")),
      [{ currentRun: stalled, recentRuns: [] },
        { recentRuns: [finished("abandoned", "SYNCHRONIZATION_ABANDONED_BY_OPERATOR")] }]);
    renderApp("/administracion/catalogo");
    const section = await current();

    await userEvent.click(await within(section).findByRole("button", { name: "Abandonar ejecución" }));
    const confirmation = within(section).getByRole("group", { name: /^¿Abandonar la ejecución/ });
    expect(confirmation).toHaveAccessibleDescription(/solo si su proceso ya no existe/);
    await userEvent.click(within(confirmation).getByRole("button", { name: "Sí, abandonar" }));

    expect(commands.map((command) => command.path)).toEqual(["/api/v1/admin/catalogue/synchronization-runs/run-now/abandon"]);
    const heading = await within(section).findByRole("heading", { name: "Abandonada y cerrada" });
    await waitFor(() => expect(heading.closest("[role=status]")).toHaveFocus());
    const history = screen.getByRole("region", { name: "Ejecuciones recientes" });
    expect(await within(history).findByRole("heading", { name: /^Abandonada por un operador/ })).toBeVisible();
  });

  it("never offers abandonment for a run that still gives heartbeats", async () => {
    stubCommands(() => problem(500, "INTERNAL_ERROR"), [{ currentRun: running, recentRuns: [] }]);
    renderApp("/administracion/catalogo");

    expect(await within(await current()).findByRole("button", { name: "Detener sincronización" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Abandonar ejecución" })).not.toBeInTheDocument();
  });

  it.each([
    ["abandon", "SYNCHRONIZATION_RUN_NOT_STALE", 409, "La ejecución sigue dando señales"],
    ["stop", "SYNCHRONIZATION_RUN_FINISHED", 409, "La ejecución ya había terminado"],
    ["stop", "SYNCHRONIZATION_RUN_NOT_FOUND", 404, "La ejecución ya no está en el historial"],
    ["stop", "ACCESS_DENIED", 403, "Tu cuenta no puede controlar sincronizaciones"],
  ])("explains a %s rejected as %s and reads the status again when it moved", async (action, code, status, heading) => {
    const { statusReads } = stubCommands(() => problem(status, code), [{ currentRun: stalled, recentRuns: [] }]);
    renderApp("/administracion/catalogo");
    const section = await current();
    const reads = () => statusReads();
    await within(section).findByRole("button", { name: "Detener sincronización" });
    const before = reads();

    await userEvent.click(within(section).getByRole("button", { name: action === "stop" ? "Detener sincronización" : "Abandonar ejecución" }));
    await userEvent.click(within(section).getByRole("button", { name: action === "stop" ? "Sí, detener" : "Sí, abandonar" }));

    const notice = (await within(section).findByRole("heading", { name: heading })).closest("[role=status]") as HTMLElement;
    await waitFor(() => expect(notice).toHaveFocus());
    expect(notice.querySelector(".notice")).toHaveClass("notice-warning");
    if (code !== "ACCESS_DENIED") await waitFor(() => expect(reads()).toBeGreaterThan(before));
  });

  it("tells a stop that came too late from a stopped run in the history", async () => {
    stubCommands(() => problem(500, "INTERNAL_ERROR"), [{ recentRuns: [
      finished("succeeded", "SYNCHRONIZATION_COMPLETED", { stopRequestedAt: "2026-10-18T04:09:00Z", counters }),
      finished("failed", "SYNCHRONIZATION_STOPPED", { runId: "run-old", stopRequestedAt: "2026-10-17T04:01:00Z", counters }),
    ] }]);
    renderApp("/administracion/catalogo");

    const [late, stopped] = await screen.findAllByRole("article") as [HTMLElement, HTMLElement];
    expect(within(late).getByRole("heading", { name: /^Completada/ })).toBeVisible();
    expect(late).toHaveTextContent("terminó antes de llegar a un punto seguro");
    expect(within(stopped).getByRole("heading", { name: /^Detenida/ })).toBeVisible();
    expect(stopped).toHaveTextContent("sin publicar ningún juego");
  });
});
