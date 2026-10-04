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

const releasesBlock = () => screen.getByRole("region", { name: "Fechas y plataformas" });
/** The presented records: the items of the block's first list, not their flags or the disclosure. */
const releaseRows = () => {
  const [list] = within(releasesBlock()).getAllByRole("list");
  if (!list) throw new Error("The release list is missing");
  return within(list)
    .getAllByRole("listitem")
    .filter((row) => row.parentElement === list);
};
const readingButton = () => screen.getByRole("button", { name: /^Tu puntuación/ });

function credited(game = gameDetailsFixture()) {
  game.developers = [
    { companyId: "company-a", name: "Capcom Development Division 1" },
    { companyId: "company-b", name: "Studio B" },
  ];
  game.publishers = [{ companyId: "company-c", name: "Capcom" }];
  game.genres = [
    { genreId: "genre-adventure", name: "Adventure" },
    { genreId: "genre-shooter", name: "Shooter" },
  ];
  game.gameModes = [{ gameModeId: "mode-single", name: "Single player" }];
  return game;
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
  it("uses product identity and keeps the community reading apart from the visitor's own", async () => {
    serve();
    renderApp(path);
    expect(
      await screen.findByRole("heading", {
        level: 1,
        name: "Resident Evil Requiem",
      }),
    ).toBeVisible();
    const community = screen.getByRole("region", { name: "Puntuación de la comunidad" });
    expect(within(community).getByText("Sin nota todavía")).toBeVisible();
    // No mean, no temperature: the band never stands in for a missing score.
    expect(screen.queryByText(/^Temperatura:/)).not.toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByText("Seguir")).not.toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Tu puntuación" })).toBeInTheDocument();
    // Eligibility is expressed by the available reading, not a separate block.
    expect(readingButton()).not.toHaveAttribute("aria-disabled");
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
    // The mean is read as a temperature, named in words beside the authoritative number.
    expect(screen.getByText("Ardiendo")).toHaveTextContent("Temperatura: Ardiendo");
    expect(screen.getByText(/^Basada en/)).toHaveTextContent("Basada en 2 puntuaciones");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
  it("states credits, genres and game modes once, exactly as the catalogue serves them", async () => {
    const game = credited();
    game.publishers.push({ companyId: "company-d", name: "Publisher B" });
    serve(game);
    renderApp(path);
    const information = await screen.findByRole("region", { name: "Información del juego" });
    const term = (label: string) =>
      within(information).getByText(label, { selector: "dt" }).nextElementSibling;
    expect(term("Desarrollador")).toHaveTextContent("Capcom Development Division 1 y Studio B");
    expect(term("Publisher")).toHaveTextContent("Capcom y Publisher B");
    expect(within(information).getByRole("heading", { level: 3, name: "Fechas y plataformas" })).toBeVisible();
    expect(information).toContainElement(releasesBlock());
    // Genres and modes are scannable chips with the provider's own names; nothing is translated.
    const genres = within(term("Géneros") as HTMLElement).getAllByRole("listitem");
    expect(genres.map((chip) => chip.textContent)).toEqual(["Adventure", "Shooter"]);
    const modes = within(term("Modos de juego") as HTMLElement).getAllByRole("listitem");
    expect(modes.map((chip) => chip.textContent)).toEqual(["Single player"]);
    // Each fact appears once on the page, never repeated in the opening.
    expect(screen.getAllByText("Adventure")).toHaveLength(1);
    expect(screen.getAllByText(/Studio B/)).toHaveLength(1);
  });
  it("keeps developer and publisher roles distinct when companies match", async () => {
    const game = credited();
    game.publishers = [...game.developers];
    serve(game);
    renderApp(path);
    const information = await screen.findByRole("region", { name: "Información del juego" });
    for (const role of ["Desarrollador", "Publisher"]) {
      expect(within(information).getByText(role, { selector: "dt" }).nextElementSibling)
        .toHaveTextContent("Capcom Development Division 1 y Studio B");
    }
    expect(within(information).queryByText("Desarrollo y distribución")).not.toBeInTheDocument();
  });
  it("leaves unknown metadata out while keeping releases inside the information section", async () => {
    const game = gameDetailsFixture();
    game.genres = [{ genreId: "genre-rpg", name: "Role-playing (RPG)" }];
    serve(game);
    const { unmount } = renderApp(path);
    const information = await screen.findByRole("region", { name: "Información del juego" });
    expect(within(information).getByText("Géneros")).toBeVisible();
    for (const missing of ["Desarrollador", "Publisher", "Modos de juego"]) {
      expect(within(information).queryByText(missing)).not.toBeInTheDocument();
    }
    unmount();

    serve(gameDetailsFixture());
    renderApp(path);
    await screen.findByRole("heading", { level: 1, name: "Resident Evil Requiem" });
    const releaseInformation = screen.getByRole("region", { name: "Información del juego" });
    expect(releaseInformation).toContainElement(releasesBlock());
    expect(within(releaseInformation).queryByText("Desarrollador")).not.toBeInTheDocument();
    expect(within(releaseInformation).queryByText("Publisher")).not.toBeInTheDocument();
    expect(releasesBlock()).toBeVisible();
  });
  it("lists every platform and region with its presented release and keeps further records whole behind a disclosure", async () => {
    const user = userEvent.setup();
    const game = gameDetailsFixture();
    const original = game.releases[0];
    if (!original) throw new Error("Fixture needs a release");
    // The API lists platform by platform with each combination's presented release first.
    game.releases.push(
      {
        ...original,
        releaseId: "ps5-europe-estimate",
        releaseDate: { precision: "year", value: "2027" },
        status: "scheduled",
      },
      {
        ...original,
        releaseId: "pc-world",
        platform: { platformId: "pc", name: "Windows PC" },
        region: { regionId: "worldwide", name: "Mundial" },
        releaseDate: { precision: "quarter", value: "2027-Q2" },
        status: "scheduled",
      },
      {
        ...original,
        releaseId: "pc-europe",
        platform: { platformId: "pc", name: "Windows PC" },
        releaseDate: { precision: "unknown", value: null },
        status: "delayed",
        reviewStatus: "required",
      },
    );
    serve(game);
    renderApp("/games/30000000-0000-4000-8000-000000000005/resident-evil-requiem?platformId=pc&regionId=europe");
    await screen.findByRole("heading", { level: 1, name: game.canonicalTitle });

    // No selector to operate first: every combination reads at once, in the API's order, and a
    // legacy selection in the URL changes nothing.
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
    const rows = releaseRows();
    expect(rows).toHaveLength(3);
    const [ps5, pcWorld, pcEurope] = rows;
    expect(ps5).toHaveTextContent(/PlayStation 5.*Europa.*27 de febrero de 2026.*Publicado/);
    expect(pcWorld).toHaveTextContent(/Windows PC.*Mundial.*2\.º trimestre de 2027.*Programado/);
    expect(pcEurope).toHaveTextContent(/Windows PC.*Europa.*Fecha por confirmar.*Retrasado/);
    expect(within(pcEurope as HTMLElement).getByText("Información pendiente de revisión")).toBeVisible();
    // Each value is still named for assistive technology.
    expect(within(ps5 as HTMLElement).getByText("Región", { selector: "dt" })).toHaveClass("sr-only");

    const more = within(releasesBlock()).getByText("Otras fechas registradas (1)");
    expect(within(releasesBlock()).getByText("2027")).not.toBeVisible();
    await user.click(more);
    expect(within(releasesBlock()).getByText("2027")).toBeVisible();
    // The ratings belong to the whole game, whatever the release context.
    expect(readingButton()).not.toHaveAttribute("aria-disabled");
    expect(gameRequests()).toHaveLength(1);
  });
  it("flags a pending review among the further records on the disclosure itself", async () => {
    const game = gameDetailsFixture();
    const original = game.releases[0];
    if (!original) throw new Error("Fixture needs a release");
    game.releases.push({
      ...original,
      releaseId: "pending-review",
      releaseDate: { precision: "day", value: "2025-11-20" },
      reviewStatus: "required",
    });
    serve(game);
    renderApp(path);
    const more = await screen.findByText("Otras fechas registradas (1)");
    expect(more.closest("summary")).toHaveTextContent("Información pendiente de revisión");
  });
  it("marks each platform and region with its own icon", async () => {
    const game = gameDetailsFixture();
    const original = game.releases[0];
    if (!original) throw new Error("Fixture needs a release");
    game.releases.push(
      { ...original, releaseId: "ps5-asia", region: { regionId: "asia", name: "Asia" } },
      { ...original, releaseId: "ps5-nz", region: { regionId: "new-zealand", name: "Nueva Zelanda" } },
      { ...original, releaseId: "ps5-cn", region: { regionId: "china", name: "China" } },
    );
    serve(game);
    renderApp(path);
    await screen.findByRole("heading", { level: 1, name: game.canonicalTitle });
    // Marks are decorative, so they are read from the value they lead.
    const mark = (name: string) =>
      within(releasesBlock())
        .getAllByText(name)[0]
        ?.closest("dd")
        ?.querySelector("[class*='app-select-icon-']")?.className;

    expect(mark("PlayStation 5")).toContain("app-select-icon-playstation-5");
    expect(mark("Europa")).toContain("app-select-icon-europe");
    expect(mark("Asia")).toContain("app-select-icon-asia");
    expect(mark("Nueva Zelanda")).toContain("app-select-icon-new-zealand");
    // A region without a supplied mark keeps the generic location marker.
    expect(mark("China")).toBeUndefined();
  });
  it("names a known stage under its date and leaves an unknown one unstated", async () => {
    const game = gameDetailsFixture();
    const original = game.releases[0];
    if (!original) throw new Error("Fixture needs a release");
    original.stage = "full_release";
    game.releases.push({
      ...original,
      releaseId: "pc-world",
      stage: "unknown",
      platform: { platformId: "pc", name: "Windows PC" },
    });
    serve(game);
    renderApp(path);
    expect(await screen.findByText("Lanzamiento completo")).toBeVisible();
    expect(screen.queryByText("Tipo no especificado")).not.toBeInTheDocument();
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
    // Ineligibility makes the personal reading unavailable and explains why in place.
    expect(readingButton()).toHaveAttribute("aria-disabled", "true");
    expect(
      screen.getByText(/pendiente de revisión\.$/),
    ).toBeVisible();
    expect(screen.getByText("Datos locales desactualizados")).toBeVisible();
    expect(within(releasesBlock()).getByText("Información pendiente de revisión")).toBeVisible();
    const community = screen.getByRole("region", {
      name: "Puntuación de la comunidad",
    });
    expect(within(community).getByRole("status")).toHaveTextContent(
      "Las estadísticas no están disponibles temporalmente",
    );
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
  it("states the release sources and the latest synchronization once for the whole block", async () => {
    const game = gameDetailsFixture();
    const original = game.releases[0];
    if (!original) throw new Error("Fixture needs a release");
    game.releases.push({
      ...original,
      releaseId: "pc-world",
      platform: { platformId: "pc", name: "Windows PC" },
      provenance: { ...original.provenance, sourceName: "PC source" },
      lastSyncedAt: "2026-08-10T08:00:00Z",
      verificationLevel: "provider_only",
    });
    serve(game);
    renderApp(path);
    expect(
      await within(await screen.findByRole("region", { name: "Fechas y plataformas" })).findByText(
        /^Fuentes: Publisher, PC source/,
      ),
    ).toHaveTextContent("Fuentes: Publisher, PC source · Sincronizado el 10 de agosto de 2026");
    // Verified evidence is credited on its own record; provider-only records stay quiet.
    expect(within(releasesBlock()).getAllByText("Información verificada")).toHaveLength(1);
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
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
    expect(within(releasesBlock()).queryByText(/^Fuente/)).not.toBeInTheDocument();
    expect(readingButton()).toHaveAttribute("aria-disabled", "true");
    expect(screen.queryByText("Géneros")).not.toBeInTheDocument();
    expect(screen.queryByText("Desarrollo")).not.toBeInTheDocument();
  });
  it("credits sourced text in its own language and replaces failed provider covers", async () => {
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
    const summary = screen.getByRole("region", { name: "Resumen" });
    // The text stays in its source language; the page only says which language that is.
    expect(within(summary).getByText(/Fuente: Publisher$/)).toHaveTextContent(
      "Texto original en inglés · Fuente: Publisher",
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
  it("keeps the catalogue's own editorial text without a source credit", async () => {
    serve();
    renderApp(path);
    const summary = await screen.findByRole("region", { name: "Resumen" });
    expect(within(summary).getByText("Resumen de prueba del catálogo.")).toHaveAttribute("lang", "es");
    expect(within(summary).queryByText(/Fuente/)).not.toBeInTheDocument();
    // jsdom has no layout, so a short text never offers to expand.
    expect(within(summary).queryByRole("button")).not.toBeInTheDocument();
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
