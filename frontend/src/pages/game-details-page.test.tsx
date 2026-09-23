import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { gameDetailsFixture } from "../test/game-details-fixture";
import { renderApp } from "../test/render-app";

const path =
  "/games/30000000-0000-4000-8000-000000000005/resident-evil-requiem";
afterEach(() => vi.unstubAllGlobals());
function requestUrl(input: unknown): string {
  if (input instanceof Request) return input.url;
  if (input instanceof URL) return input.toString();
  return String(input);
}

// The header reads BFF session state, so tests route by URL rather than assuming the
// only network call is the public game read.
function serve(game = gameDetailsFixture()) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: unknown) => {
      const url = requestUrl(input);
      if (url.includes("/api/v1/session")) {
        return Response.json({ authenticated: false });
      }
      if (url.includes("/auth/rating-intent")) {
        return new Response(null, { status: 404 });
      }
      return Response.json(game);
    }),
  );
}

function gameRequests() {
  return vi
    .mocked(fetch)
    .mock.calls.map((call) => call[0])
    .filter(
      (input): input is Request =>
        input instanceof Request && input.url.includes("/api/v1/games/"),
    );
}

describe("public game details", () => {
  it("announces loading while the requested game is pending", () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => new Promise<Response>(() => {})),
    );
    renderApp(path);
    expect(screen.getByRole("status")).toHaveTextContent("Cargando el juego");
  });
  it("uses product identity and keeps eligibility, community and personal context separate", async () => {
    serve();
    renderApp(path);
    expect(
      await screen.findByRole("heading", {
        level: 1,
        name: "Resident Evil Requiem",
      }),
    ).toBeVisible();
    expect(
      screen.getByRole("heading", { name: "Puntuaciones de la comunidad" }),
    ).toBeVisible();
    expect(screen.getByText("Sin nota todavía")).toBeVisible();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(
      screen.queryByText("Lanzamientos y evidencia"),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("Seguir")).not.toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Tu puntuación" }),
    ).toBeVisible();
    // Eligibility is expressed by the enabled 1-10 control, not a separate block.
    expect(screen.getByRole("button", { name: "8" })).toBeEnabled();
    expect(
      screen.queryByText(/Disponible para puntuar/),
    ).not.toBeInTheDocument();
    expect(screen.queryByText(/Contexto personal/)).not.toBeInTheDocument();
    expect(screen.queryByRole("spinbutton")).not.toBeInTheDocument();
    const request = gameRequests()[0];
    expect(request).toBeInstanceOf(Request);
    expect(request?.url).toContain(
      "/api/v1/games/30000000-0000-4000-8000-000000000005",
    );
  });
  it("renders the Spanish aggregate mean prominently without the distribution", async () => {
    const game = gameDetailsFixture();
    game.ratingStatistics = {
      status: "available",
      mean: 8.5,
      count: 2,
      distribution: {
        "1": 0,
        "2": 0,
        "3": 0,
        "4": 0,
        "5": 0,
        "6": 0,
        "7": 0,
        "8": 1,
        "9": 1,
        "10": 0,
      },
    };
    serve(game);
    renderApp(path);
    expect(await screen.findByLabelText("Nota media: 8,5 de 10")).toBeVisible();
    expect(screen.getByText("2 puntuaciones")).toBeVisible();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
  it("preserves uncertain dates, stale review state and a degraded aggregate", async () => {
    const game = gameDetailsFixture();
    game.releases = game.releases.map((r) => ({
      ...r,
      releaseDate: { precision: "unknown", value: null },
      reviewStatus: "required",
      freshnessStatus: "stale",
    }));
    game.ratingEligibility = {
      eligible: false,
      reason: "RELEASE_REVIEW_REQUIRED",
      evaluatedOn: "2026-08-13",
    };
    game.ratingStatistics = {
      status: "unavailable",
      reasonCode: "RATING_STATISTICS_READ_FAILED",
    };
    serve(game);
    renderApp(path);
    expect(await screen.findByText(/Fecha por confirmar/)).toBeVisible();
    // Ineligibility disables the personal control and explains why in place.
    expect(screen.getByRole("button", { name: "8" })).toBeDisabled();
    expect(
      screen.getByText(/pendiente de revisión\.$/),
    ).toBeVisible();
    expect(screen.getByText("Datos locales desactualizados")).toBeVisible();
    const community = screen.getByRole("region", {
      name: "Puntuaciones de la comunidad",
    });
    expect(within(community).getByRole("status")).toHaveTextContent(
      "Las estadísticas no están disponibles temporalmente",
    );
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
  it("changes platform and region evidence without changing global eligibility or requesting data again", async () => {
    const user = userEvent.setup();
    const game = gameDetailsFixture();
    const original = game.releases[0];
    if (!original) throw new Error("Fixture needs a release");
    game.releases.push(
      {
        ...original,
        releaseId: "pc-world",
        platform: { platformId: "pc", name: "Windows PC" },
        region: { regionId: "worldwide", name: "Worldwide" },
        releaseDate: { precision: "quarter", value: "2027-Q2" },
        status: "scheduled",
        provenance: { ...original.provenance, sourceName: "PC source" },
      },
      {
        ...original,
        releaseId: "pc-europe",
        platform: { platformId: "pc", name: "Windows PC" },
        releaseDate: { precision: "unknown", value: null },
        reviewStatus: "required",
      },
    );
    serve(game);
    renderApp(path);
    await screen.findByRole("heading", { level: 1, name: game.canonicalTitle });
    await user.click(screen.getByRole("radio", { name: "Windows PC" }));
    expect(screen.getByRole("radio", { name: "Europa" })).toBeChecked();
    const context = screen.getByRole("region", {
      name: "Contexto de lanzamiento",
    });
    expect(within(context).getByText("Fecha por confirmar")).toBeVisible();
    await user.click(screen.getByRole("radio", { name: "Mundial" }));
    expect(within(context).getByText("2.º trimestre de 2027")).toBeVisible();
    expect(within(context).getByText("PC source")).toBeVisible();
    expect(
      within(context).queryByText("Fecha por confirmar"),
    ).not.toBeInTheDocument();
    // The personal control stays game-wide and enabled whatever the selected tuple.
    expect(screen.getByRole("button", { name: "8" })).toBeEnabled();
    await user.click(screen.getByRole("radio", { name: "PlayStation 5" }));
    expect(screen.getByRole("radio", { name: "Europa" })).toBeChecked();
    expect(
      screen.queryByRole("radio", { name: "Mundial" }),
    ).not.toBeInTheDocument();
    expect(gameRequests()).toHaveLength(1);
  });
  it("keeps multiple records for the selected tuple and tolerates obsolete context parameters", async () => {
    const game = gameDetailsFixture();
    const original = game.releases[0];
    if (!original) throw new Error("Fixture needs a release");
    game.releases.push({
      ...original,
      releaseId: "second",
      releaseDate: { precision: "year", value: "2027" },
    });
    serve(game);
    renderApp(path + "?platformId=obsolete&regionId=obsolete");
    await screen.findByRole("heading", { level: 1, name: game.canonicalTitle });
    const context = screen.getByRole("region", {
      name: "Contexto de lanzamiento",
    });
    expect(within(context).getByText("27 de febrero de 2026")).toBeVisible();
    expect(within(context).getByText("2027")).toBeVisible();
    expect(screen.getByRole("radio", { name: "PlayStation 5" })).toBeChecked();
  });
  it("renders no release context without inventing selectors or metadata", async () => {
    const game = gameDetailsFixture();
    game.releases = [];
    game.ratingEligibility = {
      ...game.ratingEligibility,
      eligible: false,
      reason: "NO_COMMERCIAL_RELEASE",
    };
    serve(game);
    renderApp(path);
    expect(
      await screen.findByText("No hay lanzamientos comerciales registrados."),
    ).toBeVisible();
    expect(
      screen.queryByRole("radio", { name: "PlayStation 5" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "8" })).toBeDisabled();
    expect(screen.queryByText("Género")).not.toBeInTheDocument();
    expect(screen.queryByText("Desarrolladora")).not.toBeInTheDocument();
  });
  it("attributes sourced text and replaces failed provider covers", async () => {
    const game = gameDetailsFixture();
    game.summary = {
      kind: "sourced",
      text: "English summary",
      language: "en",
      provenance: {
        sourceKind: "official_source",
        sourceName: "Publisher",
        sourceEntityType: "summary",
      },
    };
    game.primaryCover = {
      kind: "provider",
      url: "https://images.igdb.com/igdb/image/upload/t_cover_big/co1234.webp",
      alternativeText: "Provider cover",
      attribution: {
        label: "IGDB",
        sourceUrl: "https://www.igdb.com/games/example",
      },
    };
    serve(game);
    renderApp(path);
    expect(await screen.findByText("English summary")).toHaveAttribute(
      "lang",
      "en",
    );
    expect(screen.getByRole("link", { name: "IGDB" })).toBeVisible();
    fireEvent.error(screen.getByRole("img"));
    expect(screen.getByRole("img")).toHaveAttribute(
      "src",
      "/assets/covers/fallback.svg",
    );
    expect(
      screen.queryByRole("link", { name: "IGDB" }),
    ).not.toBeInTheDocument();
  });
  it.each([
    ["GAME_NOT_FOUND", "Juego no encontrado"],
    ["CATALOGUE_NOT_READY", "El catálogo todavía no está disponible"],
  ])("explains %s", async (code, title) => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        Response.json(
          { code, correlationId: "test-correlation" },
          { status: code === "GAME_NOT_FOUND" ? 404 : 503 },
        ),
      ),
    );
    renderApp(path);
    expect(await screen.findByRole("heading", { name: title })).toBeVisible();
    expect(
      screen.getByRole("link", { name: "Volver a lanzamientos" }),
    ).toBeVisible();
  });
  it("offers a deliberate keyboard retry for network failures", async () => {
    const user = userEvent.setup();
    let gameAttempts = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: unknown) => {
        const url = requestUrl(input);
        if (url.includes("/api/v1/session")) {
          return Response.json({ authenticated: false });
        }
        if (url.includes("/auth/rating-intent")) {
          return new Response(null, { status: 404 });
        }
        gameAttempts += 1;
        if (gameAttempts === 1) {
          throw new TypeError("Network failure");
        }
        return Response.json(gameDetailsFixture());
      }),
    );
    renderApp(path);
    const retry = await screen.findByRole("button", { name: "Reintentar" });
    retry.focus();
    await user.keyboard("{Enter}");
    expect(
      await screen.findByRole("heading", { name: "Resident Evil Requiem" }),
    ).toBeVisible();
  });
});
