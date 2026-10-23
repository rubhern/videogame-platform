import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { renderApp } from "../../test/render-app";
import type { HistoricalStatus } from "./administration-api";

const stopped: HistoricalStatus = {
  state: "stopped", backfillId: "target-1", from: "2017-03-01", to: "2026-09-30", coveredMonths: "skip",
  currentFrom: "2020-11-01", currentTo: "2020-11-30", totalMonths: 115, completedMonths: 44,
  skippedMonths: 2, remainingMonths: 71, progressPercent: 38, processedGames: 3000,
  processedReleases: 6000, dataQualityFailedGames: 3, startedAt: "2026-10-23T07:00:00Z",
  lastActivityAt: "2026-10-23T08:00:00Z", elapsedSeconds: 3600, lastReason: "HISTORICAL_STOPPED",
};
function stub(initial: HistoricalStatus, failure?: string) {
  let snapshot = initial;
  const commands: Request[] = [];
  vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const request = input instanceof Request ? input : new Request(input);
    const path = new URL(request.url).pathname;
    if (path === "/api/v1/session") return Response.json({ authenticated: true, csrfToken: "csrf-proof", capabilities: ["catalogue_administration"] });
    if (path === "/api/v1/admin/catalogue/synchronization") return Response.json({ recentRuns: [],
      currentRun: { runId: "ordinary-run", from: "2026-10-01", to: "2026-10-31", startedAt: "2026-10-23T08:00:00Z", lastHeartbeatAt: "2026-10-23T08:01:00Z", stalled: false } });
    if (request.method === "POST") {
      commands.push(request);
      if (failure) return Response.json({ code: failure, correlationId: "support-1" }, { status: 409 });
      snapshot = { ...stopped, state: path.endsWith("stop") ? "stopping" : "running" };
    }
    return Response.json(snapshot);
  }));
  return commands;
}
afterEach(() => vi.unstubAllGlobals());

describe("historical administration", () => {
  it("restores server progress on reload and keeps ordinary controls outside the historical tab", async () => {
    stub(stopped); renderApp("/administracion/catalogo?tab=historica");
    expect(await screen.findByText("Detenida")).toBeVisible();
    expect(screen.getByText("44 de 115; 2 omitidos por cobertura")).toBeVisible();
    expect(screen.getByText("71")).toBeVisible();
    expect(screen.getByRole("progressbar", { name: "Progreso por meses" })).toHaveAttribute("value", "38");
    expect(screen.getByText(/Hay una sincronización periódica o manual/)).toBeVisible();
    expect(screen.queryByRole("button", { name: "Iniciar histórico" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Detener sincronización" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Solicitar sincronización" })).not.toBeInTheDocument();
  });
  it("explains when historical progression is waiting for scheduled updates", async () => {
    stub({ ...stopped, state: "waiting", lastReason: "SCHEDULED_SYNCHRONIZATION_PENDING" });
    renderApp("/administracion/catalogo?tab=historica");
    expect(await screen.findByText("Esperando otra sincronización")).toBeVisible();
    expect(screen.getByText(/Una sincronización programada tiene el siguiente turno/)).toBeVisible();
    expect(screen.getByRole("button", { name: "Detener histórico" })).toBeEnabled();
    expect(screen.queryByRole("button", { name: "Reanudar histórico" })).not.toBeInTheDocument();
  });
  it("requires explicit confirmation and sends the target and session CSRF proof only", async () => {
    const commands = stub({ state: "not_started" }); renderApp("/administracion/catalogo?tab=historica");
    const start = await screen.findByRole("button", { name: "Iniciar histórico" });
    await userEvent.selectOptions(screen.getByLabelText("Meses ya cubiertos"), "rerun");
    await userEvent.click(start);
    const group = screen.getByRole("group", { name: "Confirmar operación histórica" });
    await waitFor(() => expect(within(group).getByRole("button", { name: "Cancelar" })).toHaveFocus());
    expect(commands).toHaveLength(0);
    await userEvent.keyboard("{Escape}"); expect(start).toHaveFocus();
    await userEvent.click(start);
    await userEvent.click(screen.getByRole("button", { name: "Confirmar inicio" }));
    await waitFor(() => expect(commands).toHaveLength(1));
    expect(commands[0]?.headers.get("X-CSRF-Token")).toBe("csrf-proof");
    expect(await commands[0]?.json()).toEqual({ from: "2017-03-01", to: "2026-09-30", coveredMonths: "rerun" });
    expect(await screen.findByText(/Solicitud registrada/)).toBeVisible();
  });
  it("stops only the named historical target while another ordinary run is active", async () => {
    const commands = stub({ ...stopped, state: "running", currentRunId: "historical-run", currentProcessedGames: 10,
      currentProcessedReleases: 40, currentFailedGames: 2, currentDataQualityFailedGames: 2 });
    renderApp("/administracion/catalogo?tab=historica");
    await userEvent.click(await screen.findByRole("button", { name: "Detener histórico" }));
    await userEvent.click(screen.getByRole("button", { name: "Confirmar detención" }));
    await waitFor(() => expect(commands).toHaveLength(1));
    expect(new URL(commands[0]?.url ?? "").pathname).toBe("/api/v1/admin/catalogue/historical-synchronization/target-1/stop");
    expect(await screen.findByText("Deteniéndose")).toBeVisible();
  });
  it("resumes an interrupted target explicitly and preserves a conflict explanation", async () => {
    const commands = stub({ ...stopped, state: "interrupted" }, "HISTORICAL_SYNCHRONIZATION_CONFLICT");
    renderApp("/administracion/catalogo?tab=historica");
    await userEvent.click(await screen.findByRole("button", { name: "Reanudar histórico" }));
    await userEvent.click(screen.getByRole("button", { name: "Confirmar reanudación" }));
    expect(await screen.findByText(/Otra ejecución sigue activa/)).toBeVisible();
    expect(new URL(commands[0]?.url ?? "").pathname).toBe("/api/v1/admin/catalogue/historical-synchronization/target-1/resume");
    expect(screen.getByText("Referencia: support-1")).toBeVisible();
    expect(screen.getByText("44 de 115; 2 omitidos por cobertura")).toBeVisible();
  });
  it("distinguishes completed months with data quality from paused operational failures", async () => {
    stub({ ...stopped, state: "completed", completedMonths: 115, remainingMonths: 0, progressPercent: 100 });
    renderApp("/administracion/catalogo?tab=historica");
    expect(await screen.findByText("Completada")).toBeVisible();
    expect(screen.getByText("Fallos conocidos de calidad de datos")).toBeVisible();
    expect(screen.queryByRole("button", { name: "Reanudar histórico" })).not.toBeInTheDocument();
    expect(screen.getByText(/Un mes completado puede tener fallos/)).toBeVisible();
  });
});
