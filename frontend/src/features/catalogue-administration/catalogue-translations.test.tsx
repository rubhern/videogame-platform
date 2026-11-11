import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { renderApp } from "../../test/render-app";
import type { TranslationCoverage, TranslationExecution } from "./administration-api";

const coverage: TranslationCoverage = {
  observedAt: "2026-11-11T07:00:00Z",
  rows: [
    { kind: "summary", status: "current", origin: "machine", lastValidRetained: false, items: 6 },
    { kind: "summary", status: "retryable", lastValidRetained: true, items: 1 },
    { kind: "summary", status: "busy", lastValidRetained: false, items: 1 },
    { kind: "summary", status: "not_applicable", lastValidRetained: false, items: 2 },
    { kind: "genre", status: "current", origin: "curated", lastValidRetained: false, items: 1 },
  ],
};
const running: TranslationExecution = {
  state: "running", executionId: "bd28692f-d2ae-4a18-9f54-c0001fa158f0",
  currentKind: "summary", estimatedTotal: 40, processed: 20, succeeded: 4, skipped: 15, failed: 1,
  startedAt: "2026-11-11T07:00:00Z", lastActivityAt: "2026-11-11T07:02:00Z",
};

function stub(initial: TranslationExecution | null, options: { coverage?: TranslationCoverage; conflict?: boolean } = {}) {
  let execution = initial;
  const commands: Request[] = [];
  const paths: string[] = [];
  vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const request = input instanceof Request ? input : new Request(input);
    const path = new URL(request.url).pathname;
    paths.push(path);
    if (path === "/api/v1/session") return Response.json({ authenticated: true, csrfToken: "csrf-proof", capabilities: ["catalogue_administration"] });
    if (path === "/api/v1/admin/catalogue/synchronization") return Response.json({ recentRuns: [] });
    if (path === "/api/v1/admin/catalogue/translations/coverage") return Response.json(options.coverage ?? coverage);
    if (path !== "/api/v1/admin/catalogue/translations/execution") return Response.json({}, { status: 404 });
    if (request.method === "POST") {
      commands.push(request);
      if (options.conflict) return Response.json({ code: "TRANSLATION_EXECUTION_CONFLICT" }, { status: 409 });
      const body = await request.clone().json() as { action: string };
      execution = { ...running, state: body.action === "stop" ? "stopping" : "running" };
      return Response.json(execution, { status: 202 });
    }
    return execution ? Response.json(execution) : Response.json({}, { status: 503 });
  }));
  return { commands, paths };
}

const path = "/administracion/catalogo?tab=traducciones";
afterEach(() => vi.unstubAllGlobals());

describe("catalogue translation administration", () => {
  it("distinguishes persisted coverage from run progress across the three kinds", async () => {
    stub(running);
    renderApp(path);
    const tab = await screen.findByRole("tab", { name: "Traducciones" });
    expect(tab).toHaveAttribute("aria-selected", "true");
    const summaries = within(await screen.findByRole("region", { name: "Resúmenes" }));
    expect(summaries.getByText(/75\s*%/)).toBeVisible();
    expect(summaries.getByText(/6 · 60\s*%/)).toBeVisible();
    expect(summaries.getByText("Traducción anterior conservada")).toBeVisible();
    expect(within(screen.getByRole("region", { name: "Géneros" })).getByText("Etiquetas curadas")).toBeVisible();
    expect(within(screen.getByRole("region", { name: "Modos de juego" })).getByText("Sin contenido registrado.")).toBeVisible();
    expect(await screen.findByRole("progressbar", { name: /Progreso estimado del recorrido/ })).toHaveAttribute("value", "50");
    expect(screen.getByText("20 de 40 estimados")).toBeVisible();
  });

  it("requires explicit confirmation, restores focus on cancel, and sends the same-origin CSRF proof", async () => {
    const { commands, paths } = stub({ state: "not_started" }, { coverage: { ...coverage, rows: [] } });
    renderApp(path);
    const start = await screen.findByRole("button", { name: "Ejecutar traducciones pendientes" });
    await userEvent.click(start);
    expect(screen.getByRole("button", { name: "Cancelar" })).toHaveFocus();
    expect(commands).toHaveLength(0);
    await userEvent.keyboard("{Escape}");
    expect(start).toHaveFocus();
    await userEvent.click(start);
    await userEvent.click(screen.getByRole("button", { name: "Confirmar" }));
    await waitFor(() => expect(commands).toHaveLength(1));
    expect(commands[0]?.headers.get("X-CSRF-Token")).toBe("csrf-proof");
    expect(await commands[0]?.json()).toEqual({ action: "start" });
    expect(await screen.findByText("En curso")).toBeVisible();
    expect(paths.some((value) => value.includes("actuator"))).toBe(false);
  });

  it("requests a stop for the observed execution", async () => {
    const { commands } = stub(running);
    renderApp(path);
    await userEvent.click(await screen.findByRole("button", { name: "Detener traducciones" }));
    await userEvent.click(screen.getByRole("button", { name: "Confirmar" }));
    expect(await screen.findByText("Detención solicitada")).toBeVisible();
    expect(await commands[0]?.json()).toEqual({ action: "stop", executionId: running.executionId });
  });

  it("requires explicit resume after interruption and explains a command conflict", async () => {
    const { commands } = stub({ ...running, state: "interrupted" }, { conflict: true });
    renderApp(path);
    await userEvent.click(await screen.findByRole("button", { name: "Reanudar traducciones" }));
    await userEvent.click(screen.getByRole("button", { name: "Confirmar" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("La ejecución cambió");
    expect(await commands[0]?.json()).toEqual({ action: "resume", executionId: running.executionId });
  });

  it("shows unavailable execution separately from available coverage", async () => {
    stub(null);
    renderApp(path);
    expect(await screen.findByRole("alert")).toHaveTextContent("El estado de ejecución no está disponible");
    expect(await screen.findByRole("region", { name: "Resúmenes" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Ejecutar traducciones pendientes" })).not.toBeInTheDocument();
  });

  it("distinguishes a completed scan with unresolved translation failures", async () => {
    stub({ ...running, state: "attention", reason: "remaining_failures" });
    renderApp(path);
    expect(await screen.findByText("Requiere atención")).toBeVisible();
    expect(screen.getByRole("progressbar", { name: /Progreso estimado del recorrido/ })).toHaveAttribute("value", "100");
    expect(screen.getByRole("button", { name: "Reintentar traducciones" })).toBeEnabled();
  });
});
