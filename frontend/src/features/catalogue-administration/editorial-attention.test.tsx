import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { renderApp } from "../../test/render-app";
import type { EditorialAttentionListDetail, EditorialAttentionOverview, EditorialAttentionPreview } from "./administration-api";

const overview: EditorialAttentionOverview = {
  prompt: { version: "editorial-anticipation@1", template: "Games between {{FROM}} and {{TO}}." },
  lists: [{
    listId: "6f1d2c3b-4a5e-4f6a-8b7c-9d0e1f2a3b4c", from: "2026-10-01", to: "2026-10-31",
    promptVersion: "editorial-anticipation@1", model: "test-model", loadedAt: "2026-11-12T09:00:00Z", entryCount: 28,
  }],
};
const preview: EditorialAttentionPreview = {
  from: "2026-11-01", to: "2026-11-30", promptVersion: "editorial-anticipation@1", model: "test-model",
  entries: [
    {
      rank: 1, title: "Gears of War: E-Day", score: 100, releaseDate: "2026-11-06", warnings: ["release_date_differs"],
      match: { gameId: "30000000-0000-4000-8000-000000000002", title: "Gears of War: E-Day", slug: "gears", matchedBy: "igdb_slug", releaseInRange: "2026-11-01" },
    },
    { rank: 2, title: "KINGDOM HEARTS Collection", score: 59, warnings: ["unmatched"] },
  ],
};
const loaded: EditorialAttentionListDetail = {
  list: {
    promptVersion: "editorial-anticipation@1", model: "test-model", loadedAt: "2026-11-12T09:00:00Z", listId: "7f1d2c3b-4a5e-4f6a-8b7c-9d0e1f2a3b4c", from: "2026-11-01", to: "2026-11-30", entryCount: 2,
  },
  entries: [],
};

function stub() {
  const posts: { path: string; body: unknown; csrf: string | null }[] = [];
  vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const request = input instanceof Request ? input : new Request(input);
    const url = new URL(request.url);
    if (url.pathname === "/api/v1/session") return Response.json({ authenticated: true, csrfToken: "csrf-proof", capabilities: ["catalogue_administration"] });
    if (url.pathname === "/api/v1/admin/catalogue/synchronization") return Response.json({ recentRuns: [] });
    if (url.pathname === "/api/v1/admin/catalogue/editorial-attention") return Response.json(overview);
    if (url.pathname === "/api/v1/games") {
      return Response.json({
        items: [{ gameId: "30000000-0000-4000-8000-000000000003", canonicalTitle: "Kingdom Hearts III" }],
        page: { page: 1, pageSize: 5, totalItems: 1, totalPages: 1 },
      });
    }
    if (request.method === "POST") {
      posts.push({ path: url.pathname, body: await request.clone().json(), csrf: request.headers.get("X-CSRF-Token") });
      if (url.pathname.endsWith("/previews")) return Response.json(preview);
      if (url.pathname.endsWith("/lists")) return Response.json(loaded, { status: 201 });
    }
    return Response.json({}, { status: 404 });
  }));
  return posts;
}

afterEach(() => vi.unstubAllGlobals());

describe("editorial attention administration", () => {
  it("shows the prompt with the chosen range ready to copy", async () => {
    stub();
    renderApp("/administracion/catalogo?tab=interes");
    expect(await screen.findByRole("tab", { name: "Interés editorial" })).toHaveAttribute("aria-selected", "true");
    const from = await screen.findByLabelText("Desde");
    await userEvent.clear(from);
    await userEvent.type(from, "2026-10-01");
    const to = screen.getByLabelText("Hasta");
    await userEvent.clear(to);
    await userEvent.type(to, "2026-10-31");
    expect(screen.getByLabelText("Prompt")).toHaveValue("Games between 2026-10-01 and 2026-10-31.");
    expect(screen.getByText(/28 juegos/)).toBeVisible();
  });

  it("refuses a range longer than a quarter before copying", async () => {
    stub();
    renderApp("/administracion/catalogo?tab=interes");
    const to = await screen.findByLabelText("Hasta");
    await userEvent.clear(to);
    await userEvent.type(to, "2027-12-31");
    expect(screen.getByText("Un periodo abarca como máximo 92 días.")).toBeVisible();
    expect(screen.getByRole("button", { name: "Copiar prompt" })).toBeDisabled();
  });

  it("previews the answer, lets the operator assign a game and loads the list", async () => {
    const posts = stub();
    renderApp("/administracion/catalogo?tab=interes");
    const answer = await screen.findByLabelText("Respuesta del modelo");
    await userEvent.click(answer);
    await userEvent.paste("{\"schemaVersion\":1}");
    await userEvent.click(screen.getByRole("button", { name: "Previsualizar" }));

    const table = within(await screen.findByRole("region", { name: "Entradas del fichero" }));
    expect(table.getByText("La fecha del fichero no coincide con el catálogo")).toBeVisible();
    expect(table.getByText("Se excluirá")).toBeVisible();
    const [, secondEntry] = table.getAllByRole("button", { name: "Elegir juego" });
    if (!secondEntry) throw new Error("The second entry has no game choice.");
    await userEvent.click(secondEntry);
    await userEvent.type(table.getByLabelText("Buscar en el catálogo"), "kingdom");
    await userEvent.click(table.getByRole("button", { name: "Buscar" }));
    await userEvent.click(await table.findByRole("button", { name: "Kingdom Hearts III" }));
    expect(table.getByText("Kingdom Hearts III (elegido a mano)")).toBeVisible();

    await userEvent.click(screen.getByRole("button", { name: "Cargar lista" }));
    expect(await screen.findByText(/cargada con 2 juegos/)).toBeVisible();
    expect(posts.at(-1)).toEqual({
      path: "/api/v1/admin/catalogue/editorial-attention/lists",
      body: { content: "{\"schemaVersion\":1}", assignments: [{ rank: 2, gameId: "30000000-0000-4000-8000-000000000003" }] },
      csrf: "csrf-proof",
    });
  });
});
