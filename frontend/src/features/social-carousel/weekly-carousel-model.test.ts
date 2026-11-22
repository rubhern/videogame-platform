import { describe, expect, it } from "vitest";

import type { components } from "../../shared/api/generated/schema";
import { toFeaturedReleasesViewModel } from "../releases/featured-releases-view-model";
import { describeSlide, MAX_GAME_SLIDES, toSlideDay, toWeeklyCarousel, type GameSlide } from "./weekly-carousel-model";

type FeaturedReleases = components["schemas"]["FeaturedReleases"];
type FeaturedReleaseItem = components["schemas"]["FeaturedReleaseItem"];
type Release = components["schemas"]["Release"];

function release(gameId: string, overrides: Partial<Release> = {}): Release {
  return {
    releaseId: `release-${gameId}`,
    gameId,
    platform: { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" },
    region: { regionId: "20000000-0000-4000-8000-000000000001", name: "Mundial" },
    releaseDate: { precision: "day", value: "2026-11-20" },
    status: "scheduled",
    stage: "full_release",
    provenance: { sourceKind: "external_provider", sourceName: "IGDB", sourceEntityType: "release_date" },
    verificationLevel: "provider_only",
    reviewStatus: "not_required",
    ...overrides,
  };
}

const attribution = { label: "IGDB", sourceUrl: "https://www.igdb.com/games/example" };

function item(gameId: string, overrides: Partial<FeaturedReleaseItem> = {}): FeaturedReleaseItem {
  return {
    gameId,
    slug: `slug-${gameId}`,
    canonicalTitle: `Juego ${gameId}`,
    primaryCover: { kind: "provider", url: "https://images.igdb.com/cover.jpg", alternativeText: "Portada", attribution },
    featuredImage: {
      kind: "artwork", presentation: "fill", url: "https://images.igdb.com/art.jpg",
      compactUrl: "https://images.igdb.com/art-small.jpg", alternativeText: "Arte", attribution,
    },
    releases: [release(gameId)],
    ...overrides,
  } as FeaturedReleaseItem;
}

function week(items: FeaturedReleaseItem[], overrides: Partial<FeaturedReleases> = {}) {
  return toFeaturedReleasesViewModel({
    granularity: "week",
    week: "2026-W47",
    evaluatedOn: "2026-11-18",
    window: { from: "2026-11-16", to: "2026-11-22" },
    navigation: { previous: "2026-W46", next: "2026-W48" },
    selection: { status: "ranked", popularityFreshness: "fresh", popularityObservedAt: "2026-11-18T05:00:00Z" },
    items,
    ...overrides,
  } as FeaturedReleases);
}

function games(carousel: ReturnType<typeof toWeeklyCarousel>): GameSlide[] {
  return carousel.status === "ready"
    ? carousel.slides.filter((slide): slide is GameSlide => slide.kind === "game")
    : [];
}

describe("toWeeklyCarousel", () => {
  it("opens with every featured cover, then the week's release, the other games in rank order and a closing slide", () => {
    const carousel = toWeeklyCarousel(week([item("a"), item("b"), item("c")]));

    expect(carousel.status).toBe("ready");
    if (carousel.status !== "ready") return;
    expect(carousel.slides.map((slide) => slide.kind)).toEqual(["cover", "game", "game", "game", "closing"]);
    expect(carousel.slides[0]).toEqual({
      kind: "cover",
      heading: { lead: "Lanzamientos de la", accent: "semana" },
      range: "16–22 nov 2026",
      covers: [
        { url: "https://images.igdb.com/cover.jpg", fit: "fill" },
        { url: "https://images.igdb.com/cover.jpg", fit: "fill" },
        { url: "https://images.igdb.com/cover.jpg", fit: "fill" },
      ],
    });
    expect(games(carousel).map((slide) => [slide.featured, slide.title]))
      .toEqual([[true, "Juego a"], [false, "Juego b"], [false, "Juego c"]]);
    expect(carousel.slides.at(-1)).toMatchObject({
      kind: "closing", host: "gameometro.com", cue: "Enlace en el perfil", credit: "Datos e imágenes: IGDB",
    });
    expect(carousel.fileStem).toBe("gameometro-lanzamientos-2026-W47");
  });

  it("never holds more than six games, whatever the response carries", () => {
    const many = Array.from({ length: 9 }, (_, index) => item(`g${index}`));

    const carousel = toWeeklyCarousel(week(many));

    expect(games(carousel)).toHaveLength(MAX_GAME_SLIDES);
    expect(carousel.status === "ready" ? carousel.slides : []).toHaveLength(8);
    expect(carousel.status === "ready" && carousel.slides[0]?.kind === "cover" ? carousel.slides[0].covers : [])
      .toHaveLength(MAX_GAME_SLIDES);
  });

  it("produces fewer slides for a week with fewer featured games", () => {
    const carousel = toWeeklyCarousel(week([item("solo")]));

    expect(carousel.status === "ready" ? carousel.slides.map((slide) => slide.kind) : [])
      .toEqual(["cover", "game", "closing"]);
  });

  it("is empty, with no slides, when the week has no featured game", () => {
    const carousel = toWeeklyCarousel(week([], { selection: { status: "no_qualifying_releases" } }));

    expect(carousel).toEqual({ status: "empty", week: "2026-W47", range: "del 16 al 22 de noviembre de 2026" });
  });

  it("seals a day-precision date with its weekday and keeps any other precision in words", () => {
    const carousel = toWeeklyCarousel(week([
      item("day"),
      item("month", { releases: [release("month", { releaseDate: { precision: "month", value: "2026-11" } })] }),
    ] as FeaturedReleaseItem[]));

    const [day, month] = games(carousel);
    expect(day).toMatchObject({ day: { day: "20", weekday: "VIERNES", monthYear: "NOV 2026" }, date: "20 de noviembre de 2026" });
    expect(month).toMatchObject({ day: null, date: "noviembre de 2026" });
    expect(toSlideDay("2026-11-24")).toEqual({ day: "24", weekday: "MARTES", monthYear: "NOV 2026" });
    expect(toSlideDay(null)).toBeNull();
  });

  it("shows every platform once with its mark, keeps an unknown one by name, and two genres", () => {
    const carousel = toWeeklyCarousel(week([item("a", {
      genres: [{ genreId: "30000000-0000-4000-8000-000000000001", name: "Aventura" },
        { genreId: "30000000-0000-4000-8000-000000000002", name: "Rol" }],
      releases: [
        release("a"),
        release("a", { releaseId: "pc", platform: { platformId: "10000000-0000-4000-8000-000000000003", name: "PC (Microsoft Windows)" } }),
        release("a", { releaseId: "ps5-eu", region: { regionId: "20000000-0000-4000-8000-000000000002", name: "Europa" } }),
        release("a", { releaseId: "stadia", releaseDate: { precision: "day", value: "2026-11-21" },
          platform: { platformId: "10000000-0000-4000-8000-000000000099", name: "Stadia" } }),
      ],
    })]));

    expect(games(carousel)[0]).toMatchObject({
      platforms: [
        { name: "PlayStation 5", logo: "playstation-5" },
        { name: "PC (Microsoft Windows)", logo: "windows" },
        { name: "Stadia", logo: null },
      ],
      genres: ["Aventura", "Rol"],
    });
  });

  it("prefers the game's landscape art, then its cover shown whole, then the product fallback", () => {
    const cover = item("cover", { featuredImage: {
      kind: "cover", presentation: "contain", url: "https://images.igdb.com/cover-big.jpg",
      compactUrl: "https://images.igdb.com/cover-small.jpg", alternativeText: "Portada", attribution,
    } });
    const none = item("none", {
      primaryCover: { kind: "fallback", url: "/assets/covers/fallback.svg", alternativeText: "Sin portada", attribution: null },
      featuredImage: { kind: "fallback", presentation: "fill", url: "/assets/featured/fallback.svg",
        compactUrl: "/assets/featured/fallback.svg", alternativeText: "Sin imagen", attribution: null },
    } as Partial<FeaturedReleaseItem>);

    const carousel = toWeeklyCarousel(week([item("art"), item("second"), cover, none]));

    expect(games(carousel).map((slide) => slide.art)).toEqual([
      { url: "https://images.igdb.com/art.jpg", fit: "fill" },
      { url: "https://images.igdb.com/art.jpg", fit: "fill" },
      { url: "https://images.igdb.com/cover-big.jpg", fit: "contain" },
      { url: "/assets/featured/fallback.svg", fit: "fill" },
    ]);
    expect(carousel.status === "ready" && carousel.slides[0]?.kind === "cover" ? carousel.slides[0].covers.at(-1) : null)
      .toEqual({ url: "/assets/covers/fallback.svg", fit: "fill" });
  });

  it("carries no score onto any slide", () => {
    const carousel = toWeeklyCarousel(week([item("rated", { scores: {
      community: { status: "available", mean: 8.4, count: 1234 },
    } })]));

    expect(JSON.stringify(carousel)).not.toMatch(/8[,.]4|valoraci/);
  });

  it("writes a ready-to-paste Spanish caption with the games, dates, platforms, link and hashtags", () => {
    const carousel = toWeeklyCarousel(week([
      item("a"),
      item("b", { releases: [release("b", { releaseDate: { precision: "unknown", value: null } })] }),
    ] as FeaturedReleaseItem[]));

    expect(carousel.status === "ready" ? carousel.caption : "").toBe([
      "Lanzamientos de la semana, del 16 al 22 de noviembre de 2026:",
      "",
      "1. Juego a: 20 de noviembre de 2026 · PlayStation 5",
      "2. Juego b: Fecha por confirmar · PlayStation 5",
      "",
      "Todos los lanzamientos de la semana, filtrados por tus plataformas, en gameometro.com",
      "",
      "#videojuegos #lanzamientos #gaming #gameometro",
    ].join("\n"));
  });

  it("describes every slide in words for assistive technology", () => {
    const carousel = toWeeklyCarousel(week([item("a"), item("b")]));
    const slides = carousel.status === "ready" ? carousel.slides : [];

    expect(slides.map(describeSlide)).toEqual([
      "Portada: Lanzamientos de la semana, 16–22 nov 2026. 2 portadas de juegos.",
      "Lanzamiento de la semana: Juego a. 20 de noviembre de 2026. PlayStation 5.",
      "Juego b. 20 de noviembre de 2026. PlayStation 5.",
      "Cierre: ¿Cuál vas a jugar? Todos los lanzamientos de la semana, filtrados por tus plataformas. gameometro.com. Enlace en el perfil. Datos e imágenes: IGDB.",
    ]);
  });
});
