import type { FeaturedArt, FeaturedItem, FeaturedReleasesViewModel } from "../releases/featured-releases-view-model";
import { formatWeekRange } from "../releases/featured-releases-view-model";
import { formatDayRange, shortMonthName } from "../../shared/catalogue/release-date";
import { isPlatformLogoName, type PlatformLogoName } from "../../shared/catalogue/platform-logo-assets";
import { platformIcon } from "../../shared/catalogue/taxonomy-icons";

/** An Instagram portrait slide; X and Bluesky accept the same 4:5 ratio. */
export const SLIDE_WIDTH = 1080;
export const SLIDE_HEIGHT = 1350;
/** Destacados ranks one featured release plus up to five more; the carousel never shows more. */
export const MAX_GAME_SLIDES = 6;
export const SITE_HOST = "gameometro.com";

/**
 * An image a slide draws. `fill` crops landscape art to the frame; `contain` shows a portrait cover
 * whole over its own blurred light, never stretched.
 */
export type SlideImage = { url: string; fit: "fill" | "contain" };

/** A calendar day set as the slide's date seal: `24` beside `MARTES` and `NOV 2026`. */
export type SlideDay = { day: string; weekday: string; monthYear: string };

export type SlidePlatform = { name: string; logo: PlatformLogoName | null };

export type CoverSlide = {
  kind: "cover";
  /** `Lanzamientos de la`, then the lit word `semana`. */
  heading: { lead: string; accent: string };
  /** `23–29 nov 2026`, as Destacados's period selector shows it. */
  range: string;
  /** Every featured game's cover, in rank order. */
  covers: SlideImage[];
};

export type GameSlide = {
  kind: "game";
  /** The period's most important release, introduced as `Lanzamiento de la semana`. */
  featured: boolean;
  title: string;
  /** The seal for a day-precision date; null when the date is a month, quarter, year or unknown. */
  day: SlideDay | null;
  /** The visible release row's date in words, which replaces the seal when there is no day. */
  date: string;
  platforms: SlidePlatform[];
  genres: string[];
  /** The game's landscape art, else its cover shown whole, else the product's own fallback. */
  art: SlideImage;
};

export type ClosingSlide = {
  kind: "closing";
  question: { lead: string; accent: string; end: string };
  lines: string[];
  host: string;
  cue: string;
  credit: string;
};

export type CarouselSlide = CoverSlide | GameSlide | ClosingSlide;

export type WeeklyCarousel =
  | {
      status: "ready";
      week: string;
      range: string;
      slides: CarouselSlide[];
      caption: string;
      /** `gameometro-lanzamientos-2026-W47`, the stem of every exported file. */
      fileStem: string;
    }
  | { status: "empty"; week: string; range: string };

const HEADING = "Lanzamientos de la semana";
const HASHTAGS = "#videojuegos #lanzamientos #gaming #gameometro";
const FALLBACK_COVER = "/assets/covers/fallback.svg";
const FALLBACK_ART = "/assets/featured/fallback.svg";
const WEEKDAYS = ["domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado"] as const;

/** The seal of a `YYYY-MM-DD` calendar day; the weekday follows from the date itself. */
export function toSlideDay(value: string | null): SlideDay | null {
  const match = value === null ? null : /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (match === null) return null;
  const [, year = "", month = "", day = ""] = match;
  const date = new Date(0);
  date.setUTCFullYear(Number(year), Number(month) - 1, Number(day));
  const monthName = shortMonthName(month);
  const weekday = WEEKDAYS[date.getUTCDay()];
  if (monthName === null || weekday === undefined) return null;
  return { day: String(Number(day)), weekday: weekday.toUpperCase(), monthYear: `${monthName.toUpperCase()} ${year}` };
}

function toImage(art: FeaturedArt | undefined): SlideImage | null {
  return art === undefined ? null : { url: art.url, fit: art.kind === "cover" ? "contain" : "fill" };
}

/** Landscape art first, whatever the featured position preferred, then the cover, then the fallback. */
function gameArt(item: FeaturedItem): SlideImage {
  return toImage(item.art.find((art) => art.kind === "artwork" || art.kind === "screenshot"))
    ?? toImage(item.art.find((art) => art.kind === "cover"))
    ?? (item.cover.kind === "provider" ? { url: item.cover.url, fit: "contain" } : { url: FALLBACK_ART, fit: "fill" });
}

/** Every platform the game launches on this week, once each and in release order. */
function platforms(item: FeaturedItem): SlidePlatform[] {
  const seen = new Set<string>();
  const result: SlidePlatform[] = [];
  for (const group of item.releaseGroups) {
    group.platforms.forEach((name, index) => {
      const icon = platformIcon(group.platformIds[index] ?? "", name);
      const logo = isPlatformLogoName(icon) ? icon : null;
      const key = logo ?? `name:${name}`;
      if (seen.has(key)) return;
      seen.add(key);
      result.push({ name, logo });
    });
  }
  return result;
}

function toGameSlide(item: FeaturedItem, featured: boolean): GameSlide {
  const [visible] = item.releaseGroups;
  return {
    kind: "game",
    featured,
    title: item.title,
    day: toSlideDay(visible?.day ?? null),
    date: visible?.date ?? "Fecha por confirmar",
    platforms: platforms(item),
    genres: item.genres.slice(0, 2).map((genre) => genre.name),
    art: gameArt(item),
  };
}

function captionLine(slide: GameSlide, position: number): string {
  const names = slide.platforms.map((platform) => platform.name);
  return `${position}. ${slide.title}: ${slide.date}${names.length > 0 ? ` · ${names.join(", ")}` : ""}`;
}

function toCaption(range: string, games: GameSlide[]): string {
  return [
    `${HEADING}, ${range}:`,
    "",
    ...games.map((game, index) => captionLine(game, index + 1)),
    "",
    `Todos los lanzamientos de la semana, filtrados por tus plataformas, en ${SITE_HOST}`,
    "",
    HASHTAGS,
  ].join("\n");
}

/**
 * The week's carousel from the same Destacados selection the site shows: a cover with every
 * featured game's cover, the most important release as `Lanzamiento de la semana`, one slide per
 * other featured game in rank order, and a closing slide. A week without featured games is
 * empty, never a carousel of blank slides. Scores stay on the site.
 */
export function toWeeklyCarousel(featured: FeaturedReleasesViewModel): WeeklyCarousel {
  const range = formatWeekRange(featured.window.from, featured.window.to);
  const week = featured.period;
  const items = featured.lead === null ? [] : [featured.lead, ...featured.others].slice(0, MAX_GAME_SLIDES);
  if (items.length === 0) return { status: "empty", week, range };
  const games = items.map((item, index) => toGameSlide(item, index === 0));
  return {
    status: "ready",
    week,
    range,
    slides: [
      {
        kind: "cover",
        heading: { lead: "Lanzamientos de la", accent: "semana" },
        range: formatDayRange(featured.window.from, featured.window.to),
        covers: items.map((item) => ({ url: item.cover.kind === "fallback" ? FALLBACK_COVER : item.cover.url, fit: "fill" })),
      },
      ...games,
      {
        kind: "closing",
        question: { lead: "¿Cuál vas a ", accent: "jugar", end: "?" },
        lines: ["Todos los lanzamientos de la semana,", "filtrados por tus plataformas"],
        host: SITE_HOST,
        cue: "Enlace en el perfil",
        credit: "Datos e imágenes: IGDB",
      },
    ],
    caption: toCaption(range, games),
    fileStem: `gameometro-lanzamientos-${week}`,
  };
}

/** The accessible description of a slide, which the drawn image cannot carry itself. */
export function describeSlide(slide: CarouselSlide): string {
  if (slide.kind === "cover") {
    const count = slide.covers.length;
    return `Portada: ${HEADING}, ${slide.range}. ${count} ${count === 1 ? "portada" : "portadas"} de juegos.`;
  }
  if (slide.kind === "closing") {
    return `Cierre: ${slide.question.lead}${slide.question.accent}${slide.question.end} ${slide.lines.join(" ")}. ${slide.host}. ${slide.cue}. ${slide.credit}.`;
  }
  const parts = [
    slide.featured ? `Lanzamiento de la semana: ${slide.title}` : slide.title,
    slide.date,
    slide.platforms.map((platform) => platform.name).join(", ") || null,
    slide.genres.join(" · ") || null,
  ];
  return `${parts.filter((part): part is string => part !== null).join(". ")}.`;
}
