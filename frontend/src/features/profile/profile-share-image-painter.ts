import logoSource from "../../shared/brand/gameometro-logo.svg?raw";
import {
  METER_BAND_EDGES,
  METER_HEAD,
  METER_NEEDLE,
  METER_RADIUS,
  METER_TRACK,
  meterArc,
  meterPoint,
  meterTurn,
} from "../../shared/score/game-meter-geometry";
import { THERMAL_BANDS, thermalBand, thermalLabels, type ThermalBand } from "../../shared/score/thermal-band";
import {
  canvasToBlob,
  clampLines,
  drawCover,
  drawLogo,
  fireFill,
  font,
  glow,
  iceFill,
  loadArt,
  loadFaces,
  loadImage,
  readColor,
  roundedPath,
  SANS,
  sizedSvg,
  type FontWidth,
} from "../../shared/share-image/canvas-kit";
import { formatPersonalScore } from "../ratings/personal-score";
import {
  gamesOrderLabels,
  hasProviderArt,
  SHARE_FORMATS,
  type CardArt,
  type CardGame,
  type ProfileCard,
  type ShareFormat,
} from "./profile-share-image-model";

/**
 * Paints a player's public profile card (#619) on a 2D canvas, so the preview is the exact PNG the
 * player shares. The stage takes its light from the lead cover, the alias is the title, and each
 * chosen block reads one shared section: the rated games, how the player scores, and Deseados.
 * Blocks scale together to fill the format, and a story keeps its copy clear of the apps' bars.
 */

type Thermal = { colour: string; deep: string };

type Palette = {
  night: string; raised: string; ink: string; muted: string; subtle: string;
  ice: string; iceLight: string; fire: string; fireLight: string;
  thermal: Record<ThermalBand, Thermal>;
};

export type CardAssets = { palette: Palette; logo: HTMLImageElement | null };

const WIDTH = 1080;
const PAD = 72;
const CONTENT = WIDTH - PAD * 2;
const COVER_RATIO = 374 / 264;
const GAP = 52;
/** From the footer's hairline to its link's baseline. */
const FOOTER = 100;
/** Where each format keeps its copy: a story leaves room for the apps' own top and bottom bars. */
const SAFE: Record<ShareFormat, { top: number; bottom: number; maxScale: number }> = {
  post: { top: 64, bottom: 64, maxScale: 1.15 },
  story: { top: 200, bottom: 240, maxScale: 1.35 },
};

/** The two temperatures of a band as thermal styles paint them, read from a probe element. */
function readThermal(band: ThermalBand): Thermal {
  const probe = document.createElement("span");
  probe.dataset.thermal = band;
  probe.hidden = true;
  document.body.append(probe);
  const styles = getComputedStyle(probe);
  const colour = styles.getPropertyValue("--thermal").trim() || readColor(band);
  const deep = styles.getPropertyValue("--thermal-deep").trim() || colour;
  probe.remove();
  return { colour, deep };
}

function readPalette(): Palette {
  return {
    night: readColor("night"), raised: readColor("raised"), ink: readColor("ink"), muted: readColor("muted"),
    subtle: readColor("subtle"), ice: readColor("ice"), iceLight: readColor("ice-light"),
    fire: readColor("fire"), fireLight: readColor("fire-light"),
    thermal: Object.fromEntries(THERMAL_BANDS.map((band) => [band, readThermal(band)])) as Record<ThermalBand, Thermal>,
  };
}

/** Waits for the faces and the lockup every card shares, so a paint never falls back. */
export async function loadCardAssets(): Promise<CardAssets> {
  await loadFaces([
    `800 96px ${SANS}`, `700 26px ${SANS}`, `600 26px ${SANS}`, `500 26px ${SANS}`,
  ]);
  const logo = await loadImage(sizedSvg(logoSource, 1140), false);
  return { palette: readPalette(), logo };
}

type Images = Map<string, HTMLImageElement | null>;

async function loadCardImages(card: ProfileCard): Promise<Images> {
  const arts: CardArt[] = [
    ...(card.games?.items.map((game) => game.cover) ?? []),
    ...(card.wishlist?.covers ?? []),
    { url: "/assets/covers/fallback.svg", provider: false },
  ];
  const urls = [...new Set(arts.map((art) => art.url))];
  const loaded = await Promise.all(urls.map(loadArt));
  return new Map(urls.map((url, index) => [url, loaded[index] ?? null]));
}

function art(images: Images, cover: CardArt): HTMLImageElement | null {
  return images.get(cover.url) ?? images.get("/assets/covers/fallback.svg") ?? null;
}

// ---------------------------------------------------------------- Stage, header and footer

/**
 * Night, lit by the lead cover's own light at a capped brightness: the art is resampled to a few
 * pixels, so only its hue remains, and gathered behind the block that shows it, as the site's
 * stages are lit by the art they present. A product fallback never lights it.
 */
function paintStage(
  context: CanvasRenderingContext2D, palette: Palette, lead: HTMLImageElement | null, height: number, focusY: number,
) {
  context.fillStyle = palette.night;
  context.fillRect(0, 0, WIDTH, height);
  const tiny = document.createElement("canvas");
  tiny.width = 18;
  tiny.height = 24;
  const sample = tiny.getContext("2d");
  const light = document.createElement("canvas");
  light.width = WIDTH;
  light.height = height;
  const spread = light.getContext("2d");
  if (lead !== null && sample !== null && spread !== null) {
    drawCover(sample, lead, 0, 0, tiny.width, tiny.height, 0.4);
    spread.imageSmoothingEnabled = true;
    spread.imageSmoothingQuality = "high";
    if ("filter" in spread) spread.filter = "blur(48px) saturate(1.2)";
    spread.drawImage(tiny, -WIDTH * 0.2, focusY - height * 0.42, WIDTH * 1.4, height * 0.84);
    spread.filter = "none";
    spread.globalCompositeOperation = "destination-in";
    const mask = spread.createRadialGradient(WIDTH / 2, focusY, 0, WIDTH / 2, focusY, WIDTH * 0.78);
    mask.addColorStop(0, "rgba(0, 0, 0, 1)");
    mask.addColorStop(0.5, "rgba(0, 0, 0, 0.5)");
    mask.addColorStop(1, "rgba(0, 0, 0, 0)");
    spread.fillStyle = mask;
    spread.fillRect(0, 0, WIDTH, height);
    context.save();
    context.globalAlpha = 0.58;
    context.drawImage(light, 0, 0);
    context.restore();
  }
  glow(context, palette.ice, WIDTH, 0, 760, 0.16);
  glow(context, palette.fire, 0, height, 560, 0.1);
}

function paintHeader(context: CanvasRenderingContext2D, assets: CardAssets, top: number) {
  drawLogo(context, assets.logo, PAD, top, 236);
  font(context, 700, 24, SANS, 4, "condensed");
  context.fillStyle = assets.palette.muted;
  context.textAlign = "right";
  context.fillText("PERFIL DE JUGADOR", WIDTH - PAD, top + 34);
  context.textAlign = "left";
}

type AliasFit = { size: number; lines: string[]; width: FontWidth };

/**
 * `@alias` at the largest size that holds it: on one line where it can stay a title, then broken
 * after a hyphen. A post keeps it smaller so the games lead; a story has room for more.
 */
function fitAlias(context: CanvasRenderingContext2D, alias: string, format: ShareFormat): AliasFit {
  const text = `@${alias}`;
  const fits = (lines: string[]) => lines.every((line) => context.measureText(line).width <= CONTENT);
  const setSize = (size: number, width: FontWidth) => font(context, 800, size, SANS, -size * 0.03, width);
  const widest = (lines: string[]) => Math.max(...lines.map((line) => context.measureText(line).width));
  const twoLines = (): string[] | null => {
    const parts = text.split(/(?<=-)/);
    let best: string[] | null = null;
    for (let cut = 1; cut < parts.length; cut += 1) {
      const lines = [parts.slice(0, cut).join(""), parts.slice(cut).join("")];
      if (fits(lines) && (best === null || widest(lines) < widest(best))) best = lines;
    }
    return best;
  };
  const story = format === "story";
  for (let size = story ? 136 : 112; size >= 72; size -= 4) {
    setSize(size, "wide");
    if (fits([text])) return { size, lines: [text], width: "wide" };
  }
  if (story) {
    for (let size = 120; size >= 72; size -= 4) {
      setSize(size, "wide");
      const lines = twoLines();
      if (lines !== null) return { size, lines, width: "wide" };
    }
  }
  for (const width of ["wide", "normal"] as const) {
    for (let size = 68; size >= 52; size -= 4) {
      setSize(size, width);
      if (fits([text])) return { size, lines: [text], width };
    }
  }
  for (let size = 72; size >= 48; size -= 4) {
    setSize(size, "wide");
    const lines = twoLines();
    if (lines !== null) return { size, lines, width: "wide" };
  }
  setSize(48, "normal");
  return { size: 48, lines: [text], width: "normal" };
}

const aliasLineHeight = (fit: AliasFit) => fit.size * 1.02;

/** The alias's bottom edge, including its fire rule. */
function aliasBottom(fit: AliasFit, top: number): number {
  return top + fit.size * 1.02 + (fit.lines.length - 1) * aliasLineHeight(fit) + 29;
}

/** The `@` takes the ice of the G; the alias stays in ink, over one short fire rule. */
function paintAlias(context: CanvasRenderingContext2D, palette: Palette, fit: AliasFit, top: number) {
  const { size, lines, width } = fit;
  font(context, 800, size, SANS, -size * 0.03, width);
  lines.forEach((line, index) => {
    const baseline = top + size * 0.82 + index * aliasLineHeight(fit);
    let x = PAD;
    if (index === 0) {
      context.fillStyle = iceFill(context, palette.iceLight, palette.ice, baseline - size * 0.7, baseline);
      context.fillText("@", x, baseline);
      x += context.measureText("@").width;
    }
    context.fillStyle = palette.ink;
    context.fillText(index === 0 ? line.slice(1) : line, x, baseline, CONTENT - (x - PAD));
  });
  // One short fire rule under the title: the image's warm accent, as the page opening carries.
  const rule = aliasBottom(fit, top) - 7;
  context.fillStyle = fireFill(context, palette.fire, palette.fireLight, PAD, 84);
  roundedPath(context, PAD, rule, 84, 7, 3.5);
  context.fill();
}

/** The profile address, so a recipient can open it, and the provider credit when covers appear. */
function paintFooter(context: CanvasRenderingContext2D, palette: Palette, card: ProfileCard, bottom: number) {
  const hairline = context.createLinearGradient(PAD, 0, WIDTH - PAD, 0);
  hairline.addColorStop(0, "rgba(157, 179, 255, 0.4)");
  hairline.addColorStop(1, "rgba(157, 179, 255, 0)");
  context.fillStyle = hairline;
  context.fillRect(PAD, bottom - FOOTER, CONTENT, 2);

  font(context, 700, 22, SANS, 3.5, "condensed");
  context.fillStyle = palette.muted;
  context.fillText("MI PERFIL EN", PAD, bottom - 54);
  if (hasProviderArt(card)) {
    font(context, 600, 20, SANS, 1, "condensed");
    context.fillStyle = palette.subtle;
    context.textAlign = "right";
    context.fillText("Datos e imágenes: IGDB", WIDTH - PAD, bottom - 54);
    context.textAlign = "left";
  }

  const slash = card.link.indexOf("/");
  const host = slash === -1 ? card.link : card.link.slice(0, slash);
  const path = slash === -1 ? "" : card.link.slice(slash);
  let size = 46;
  for (; size > 26; size -= 2) {
    font(context, 700, size, SANS, -size * 0.01, "wide");
    if (context.measureText(card.link).width <= CONTENT) break;
  }
  font(context, 700, size, SANS, -size * 0.01, "wide");
  context.fillStyle = palette.ink;
  context.fillText(host, PAD, bottom - 4, CONTENT);
  context.fillStyle = palette.iceLight;
  context.fillText(path, PAD + context.measureText(host).width, bottom - 4, CONTENT - context.measureText(host).width);
}

// ---------------------------------------------------------------- Shared pieces

/** A block's label in the readout voice, an ice hairline, and its count on the right. */
function paintKicker(context: CanvasRenderingContext2D, palette: Palette, label: string, total: string | null, y: number, s: number) {
  const baseline = y + 27 * s;
  font(context, 700, 27 * s, SANS, 3.5 * s, "condensed");
  context.fillStyle = palette.ice;
  const text = label.toUpperCase();
  context.fillText(text, PAD, baseline);
  const lineStart = PAD + context.measureText(text).width + 22 * s;
  let lineEnd = WIDTH - PAD;
  if (total !== null) {
    const count = total.toUpperCase();
    context.fillStyle = palette.muted;
    context.textAlign = "right";
    context.fillText(count, WIDTH - PAD, baseline);
    context.textAlign = "left";
    lineEnd -= context.measureText(count).width + 22 * s;
  }
  if (lineEnd > lineStart) {
    const line = context.createLinearGradient(lineStart, 0, lineEnd, 0);
    line.addColorStop(0, "rgba(157, 179, 255, 0.35)");
    line.addColorStop(1, "rgba(157, 179, 255, 0.08)");
    context.fillStyle = line;
    context.fillRect(lineStart, baseline - 9 * s, lineEnd - lineStart, Math.max(1, 1.5 * s));
  }
}

/** A cover in the standard frame: radius, quiet edge and shadow; a failed cover keeps its title. */
function paintCoverFrame(
  context: CanvasRenderingContext2D, palette: Palette, image: HTMLImageElement | null, cover: CardArt, title: string,
  x: number, y: number, width: number, height: number, s: number,
) {
  context.save();
  context.shadowColor = "rgba(0, 0, 0, 0.62)";
  context.shadowBlur = 42 * s;
  context.shadowOffsetY = 18 * s;
  context.fillStyle = palette.raised;
  roundedPath(context, x, y, width, height, 8 * s);
  context.fill();
  context.restore();
  context.save();
  roundedPath(context, x, y, width, height, 8 * s);
  context.clip();
  if (image !== null) drawCover(context, image, x, y, width, height, 0.4);
  if (!cover.provider) {
    // The product fallback carries no title of its own, so the card names the game on it.
    context.fillStyle = "rgba(6, 8, 13, 0.55)";
    context.fillRect(x, y, width, height);
    const size = Math.max(14, width * 0.11);
    font(context, 700, size, SANS, 0, "condensed");
    context.fillStyle = palette.ink;
    context.textAlign = "center";
    const lines = clampLines(context, title, width - 20 * s, 4);
    const top = y + height / 2 - ((lines.length - 1) * size * 1.15) / 2 + size * 0.35;
    lines.forEach((line, index) => context.fillText(line, x + width / 2, top + index * size * 1.15));
    context.textAlign = "left";
  }
  context.strokeStyle = "rgba(255, 255, 255, 0.1)";
  context.lineWidth = 2 * s;
  context.stroke();
  context.restore();
}

/**
 * The step a game stands on: its temperature in words over the score's thermal band, with its
 * rank among the highest scores. It sits under the cover, so the art itself stays whole.
 */
function paintPlinth(
  context: CanvasRenderingContext2D, palette: Palette, game: CardGame, x: number, y: number, width: number, s: number,
) {
  const { colour, deep } = palette.thermal[game.band];
  const height = PLINTH * s;
  context.save();
  const fill = context.createLinearGradient(x, 0, x + width, 0);
  fill.addColorStop(0, deep);
  fill.addColorStop(1, colour);
  context.fillStyle = fill;
  roundedPath(context, x, y, width, height, 6 * s);
  context.fill();
  const baseline = y + height / 2 + 8.5 * s;
  context.fillStyle = palette.night;
  font(context, 800, 24 * s, SANS, 2.4 * s, "condensed");
  const word = thermalLabels[game.band].toUpperCase();
  if (game.rank === null) {
    context.textAlign = "center";
    context.fillText(word, x + width / 2, baseline);
  } else {
    context.textAlign = "right";
    context.fillText(word, x + width - 14 * s, baseline);
    font(context, 800, 30 * s, SANS, -0.5 * s, "wide");
    context.textAlign = "left";
    context.fillText(`${game.rank}.º`, x + 14 * s, baseline + 1.5 * s);
  }
  context.textAlign = "left";
  context.restore();
}

// ---------------------------------------------------------------- Rated games

/** The step under each cover, its gap, and the score and title beneath it. */
const PLINTH = 46;
const PLINTH_GAP = 8;
const GAMES_HEIGHT = 44 + 24 + 290 * COVER_RATIO + PLINTH_GAP + PLINTH + 14 + 52 + 38 + 31 + 10;
const GAMES_WIDTH = 236 * 2 + 290 + 36 * 2;

/** The highest score stands in the middle, raised by its larger frame; latest ratings read in order. */
function arrangeGames(order: "top" | "recent", items: CardGame[]): { game: CardGame; width: number }[] {
  if (order === "recent") return items.map((game) => ({ game, width: 262 }));
  const [first, second, third] = items;
  return [second, first, third]
    .filter((game): game is CardGame => game !== undefined)
    .map((game) => ({ game, width: game === first ? 290 : 236 }));
}

function paintGames(
  context: CanvasRenderingContext2D, palette: Palette, images: Images,
  block: NonNullable<ProfileCard["games"]>, y: number, s: number,
) {
  paintKicker(context, palette, gamesOrderLabels[block.order], null, y, s);
  const columns = arrangeGames(block.order, block.items);
  const gap = 36 * s;
  const rowWidth = columns.reduce((sum, column) => sum + column.width * s, 0) + gap * (columns.length - 1);
  const base = y + (44 + 24) * s + 290 * COVER_RATIO * s;
  let x = (WIDTH - rowWidth) / 2;
  for (const { game, width: natural } of columns) {
    const width = natural * s;
    const height = width * COVER_RATIO;
    const top = base - height;
    paintCoverFrame(context, palette, art(images, game.cover), game.cover, game.title, x, top, width, height, s);
    paintPlinth(context, palette, game, x, base + PLINTH_GAP * s, width, s);

    const centre = x + width / 2;
    const scoreBaseline = base + (PLINTH_GAP + PLINTH + 14 + 52) * s;
    font(context, 800, 62 * s, SANS, -1.5 * s, "wide");
    const score = formatPersonalScore(game.score);
    const scoreWidth = context.measureText(score).width;
    font(context, 600, 26 * s, SANS, 0, "normal");
    const outOf = "/10";
    const outWidth = context.measureText(outOf).width;
    const start = centre - (scoreWidth + 6 * s + outWidth) / 2;
    font(context, 800, 62 * s, SANS, -1.5 * s, "wide");
    context.fillStyle = palette.thermal[game.band].colour;
    context.fillText(score, start, scoreBaseline);
    font(context, 600, 26 * s, SANS, 0, "normal");
    context.fillStyle = palette.muted;
    context.fillText(outOf, start + scoreWidth + 6 * s, scoreBaseline);

    font(context, 650, 25 * s, SANS, 0, "normal");
    context.fillStyle = palette.ink;
    context.textAlign = "center";
    clampLines(context, game.title, width + 24 * s, 2).forEach((line, index) =>
      context.fillText(line, centre, scoreBaseline + (38 + index * 31) * s));
    context.textAlign = "left";
    x += width + gap;
  }
}

// ---------------------------------------------------------------- How the player scores

const SCORING_HEIGHT = 44 + 22 + 200;

/** The G-meter as the site draws it, at any size: its trail and needle take the mean's temperature. */
function paintMeter(context: CanvasRenderingContext2D, palette: Palette, value: number, x: number, y: number, size: number) {
  const band = thermalBand(value);
  if (band === null) return;
  const { colour, deep } = palette.thermal[band];
  const turn = meterTurn(value);
  context.save();
  context.translate(x, y);
  context.scale(size / 64, size / 64);
  context.lineJoin = "miter";
  context.lineWidth = 6.6;
  context.strokeStyle = "rgba(200, 213, 255, 0.3)";
  context.stroke(new Path2D(METER_TRACK));
  context.lineWidth = 1.2;
  context.strokeStyle = palette.night;
  for (const edge of METER_BAND_EDGES) {
    const [x1, y1] = meterPoint(METER_RADIUS - 3.6, meterTurn(edge));
    const [x2, y2] = meterPoint(METER_RADIUS + 3.6, meterTurn(edge));
    context.beginPath();
    context.moveTo(x1, y1);
    context.lineTo(x2, y2);
    context.stroke();
  }
  if (turn > 0) {
    context.lineWidth = 6.6;
    context.globalAlpha = 0.55;
    context.strokeStyle = colour;
    context.stroke(new Path2D(meterArc(0, turn)));
    context.globalAlpha = 1;
    const from = Math.max(0, turn - METER_HEAD);
    const [hx1, hy1] = meterPoint(METER_RADIUS, from);
    const [hx2, hy2] = meterPoint(METER_RADIUS, turn);
    const head = context.createLinearGradient(hx1, hy1, hx2, hy2);
    head.addColorStop(0, "transparent");
    head.addColorStop(1, colour);
    context.strokeStyle = head;
    context.stroke(new Path2D(meterArc(from, turn)));
  }
  context.translate(32, 32);
  context.rotate((turn * Math.PI) / 180);
  context.translate(-32, -32);
  context.shadowColor = deep;
  context.shadowBlur = 3 * (size / 64);
  context.fillStyle = colour;
  context.fill(new Path2D(METER_NEEDLE));
  context.beginPath();
  context.arc(32, 32, 4.4, 0, Math.PI * 2);
  context.fill();
  context.shadowBlur = 0;
  context.fillStyle = "#fff6e6";
  context.beginPath();
  context.arc(32, 32, 1.5, 0, Math.PI * 2);
  context.fill();
  context.restore();
}

/** The tapered fire blade with its pivot, the compact scales' needle, standing at the exact mean. */
function paintMeanNeedle(context: CanvasRenderingContext2D, palette: Palette, x: number, top: number, bottom: number, s: number) {
  context.save();
  const blade = context.createLinearGradient(0, bottom, 0, top);
  blade.addColorStop(0, palette.fire);
  blade.addColorStop(1, palette.fireLight);
  context.fillStyle = blade;
  context.beginPath();
  context.moveTo(x - 6 * s, bottom);
  context.lineTo(x, top);
  context.lineTo(x + 6 * s, bottom);
  context.closePath();
  context.fill();
  context.beginPath();
  context.arc(x, bottom, 9 * s, 0, Math.PI * 2);
  context.fill();
  context.fillStyle = "#fff4dc";
  context.beginPath();
  context.arc(x, bottom, 3 * s, 0, Math.PI * 2);
  context.fill();
  context.restore();
}

function paintScoring(
  context: CanvasRenderingContext2D, palette: Palette, block: NonNullable<ProfileCard["scoring"]>, y: number, s: number,
) {
  const total = new Intl.NumberFormat("es-ES").format(block.total);
  paintKicker(context, palette, "Así puntúo", `${total} ${block.total === 1 ? "juego puntuado" : "juegos puntuados"}`, y, s);
  const top = y + (44 + 22) * s;
  const meterSize = 200 * s;
  let x = PAD - 14 * s;
  if (block.mean !== null && block.band !== null) {
    paintMeter(context, palette, block.mean, x, top, meterSize);
    x += meterSize + 18 * s;
    const { colour } = palette.thermal[block.band];
    font(context, 700, 23 * s, SANS, 3 * s, "condensed");
    context.fillStyle = palette.muted;
    context.fillText("MEDIA", x, top + 50 * s);
    font(context, 800, 104 * s, SANS, -3 * s, "wide");
    const mean = formatPersonalScore(block.mean);
    context.fillStyle = colour;
    context.fillText(mean, x, top + 142 * s);
    const meanWidth = context.measureText(mean).width;
    font(context, 600, 30 * s, SANS, 0, "normal");
    context.fillStyle = palette.muted;
    context.fillText("/10", x + meanWidth + 8 * s, top + 142 * s);
    font(context, 800, 25 * s, SANS, 3.5 * s, "condensed");
    context.fillStyle = colour;
    context.fillText(thermalLabels[block.band].toUpperCase(), x, top + 184 * s);
    x += Math.max(meanWidth + 70 * s, 200 * s) + 30 * s;
  }

  // Ten thermal columns, one per score; the fire needle stands at the exact mean behind them.
  const left = x;
  const width = WIDTH - PAD - left;
  const gap = 8 * s;
  const bar = (width - gap * 9) / 10;
  const floor = top + 160 * s;
  const tallest = 126 * s;
  const max = Math.max(...block.distribution, 1);
  if (block.mean !== null) {
    const at = left + bar / 2 + (block.mean - 1) * (bar + gap);
    paintMeanNeedle(context, palette, Math.min(Math.max(at, left), left + width), top - 6 * s, floor + 4 * s, s);
  }
  block.distribution.forEach((bucket, index) => {
    const value = index + 1;
    const band = thermalBand(value) ?? "warm";
    const { colour, deep } = palette.thermal[band];
    const columnX = left + index * (bar + gap);
    if (bucket === 0) {
      context.fillStyle = "rgba(241, 244, 250, 0.14)";
      context.fillRect(columnX, floor - 3 * s, bar, 3 * s);
    } else {
      const height = Math.max(6 * s, (bucket / max) * tallest);
      const fill = context.createLinearGradient(0, floor, 0, floor - height);
      fill.addColorStop(0, deep);
      fill.addColorStop(1, colour);
      context.save();
      context.globalAlpha = 0.94;
      context.fillStyle = fill;
      roundedPath(context, columnX, floor - height, bar, height, [3 * s, 3 * s, 0, 0]);
      context.fill();
      context.restore();
      font(context, 700, 19 * s, SANS, 0, "condensed");
      context.fillStyle = palette.ink;
      context.textAlign = "center";
      context.fillText(new Intl.NumberFormat("es-ES", { notation: "compact" }).format(bucket), columnX + bar / 2, floor - height - 8 * s);
      context.textAlign = "left";
    }
    font(context, 700, 21 * s, SANS, 0, "condensed");
    context.fillStyle = palette.muted;
    context.textAlign = "center";
    context.fillText(String(value), columnX + bar / 2, floor + 30 * s);
    context.textAlign = "left";
  });
}

// ---------------------------------------------------------------- Deseados

const WISH_ROW = { width: (CONTENT - 5 * 22) / 6, gap: 22 };
const WISH_GRID = { width: 280, gap: 28 };

/** Up to six tiles: the first covers in saved order, and one `+N` tile when more games remain. */
function wishlistTiles(block: NonNullable<ProfileCard["wishlist"]>, slots: number) {
  const covers = block.total > block.covers.length || block.covers.length > slots
    ? block.covers.slice(0, Math.min(block.covers.length, slots - 1))
    : block.covers;
  return { covers, rest: block.total - covers.length };
}

function wishlistLayout(block: NonNullable<ProfileCard["wishlist"]>, lead: boolean) {
  const { width, gap } = lead ? WISH_GRID : WISH_ROW;
  const columns = lead ? 3 : 6;
  const { covers, rest } = wishlistTiles(block, 6);
  const tiles = covers.length + (rest > 0 ? 1 : 0);
  const rows = Math.ceil(tiles / columns);
  const used = Math.min(tiles, columns);
  return {
    width, gap, columns, covers, rest, rows,
    naturalWidth: used * width + (used - 1) * gap,
    naturalHeight: 44 + 22 + rows * width * COVER_RATIO + (rows - 1) * gap,
  };
}

function paintWishlist(
  context: CanvasRenderingContext2D, palette: Palette, images: Images,
  block: NonNullable<ProfileCard["wishlist"]>, lead: boolean, y: number, s: number,
) {
  const total = new Intl.NumberFormat("es-ES").format(block.total);
  paintKicker(context, palette, "Mis deseados", block.total === 1 ? "1 juego" : `${total} juegos`, y, s);
  const layout = wishlistLayout(block, lead);
  const width = layout.width * s;
  const height = width * COVER_RATIO;
  const gap = layout.gap * s;
  const left = (WIDTH - layout.naturalWidth * s) / 2;
  const top = y + (44 + 22) * s;
  const position = (index: number) => ({
    x: left + (index % layout.columns) * (width + gap),
    y: top + Math.floor(index / layout.columns) * (height + gap),
  });
  layout.covers.forEach((cover, index) => {
    const at = position(index);
    paintCoverFrame(context, palette, art(images, cover), cover, cover.title, at.x, at.y, width, height, s);
  });
  if (layout.rest > 0) {
    const at = position(layout.covers.length);
    context.save();
    roundedPath(context, at.x, at.y, width, height, 8 * s);
    context.fillStyle = "rgba(20, 26, 37, 0.92)";
    context.fill();
    context.lineWidth = 2 * s;
    context.strokeStyle = "rgba(157, 179, 255, 0.45)";
    context.stroke();
    const size = (lead ? 76 : 40) * s;
    font(context, 800, size, SANS, -size * 0.03, "wide");
    context.fillStyle = iceFill(context, palette.iceLight, palette.ice, at.y + height / 2 - size, at.y + height / 2);
    context.textAlign = "center";
    context.fillText(`+${new Intl.NumberFormat("es-ES", { notation: "compact" }).format(layout.rest)}`, at.x + width / 2, at.y + height / 2 + size * 0.2);
    font(context, 700, (lead ? 26 : 17) * s, SANS, 2 * s, "condensed");
    context.fillStyle = palette.muted;
    context.fillText("MÁS", at.x + width / 2, at.y + height / 2 + size * 0.2 + (lead ? 44 : 28) * s);
    context.textAlign = "left";
    context.restore();
  }
}

// ---------------------------------------------------------------- An alias without blocks

const INVITATION_HEIGHT = 230;

function paintInvitation(context: CanvasRenderingContext2D, palette: Palette, y: number, s: number) {
  font(context, 800, 76 * s, SANS, -2 * s, "wide");
  context.fillStyle = palette.ink;
  context.fillText("Ven a ver", PAD, y + 76 * s);
  context.fillText("mi perfil", PAD, y + 160 * s);
  font(context, 500, 32 * s, SANS, 0, "normal");
  context.fillStyle = palette.iceLight;
  context.fillText("Lo que comparto en Gameómetro, en un enlace.", PAD, y + 220 * s, CONTENT);
}

// ---------------------------------------------------------------- Composition

type Block = {
  natural: number;
  naturalWidth: number;
  /** Where the block's art sits, for the stage's light. */
  focus?: (y: number, s: number) => number;
  paint: (y: number, s: number) => void;
};

async function compose(context: CanvasRenderingContext2D, card: ProfileCard, assets: CardAssets) {
  const { palette } = assets;
  const { height } = SHARE_FORMATS[card.format];
  const safe = SAFE[card.format];
  const images = await loadCardImages(card);

  const blocks: Block[] = [];
  if (card.games !== null) {
    const games = card.games;
    blocks.push({
      natural: GAMES_HEIGHT, naturalWidth: GAMES_WIDTH,
      focus: (y, s) => y + (44 + 24 + (290 * COVER_RATIO) / 2) * s,
      paint: (y, s) => paintGames(context, palette, images, games, y, s),
    });
  }
  if (card.scoring !== null) {
    const scoring = card.scoring;
    blocks.push({ natural: SCORING_HEIGHT, naturalWidth: 760, paint: (y, s) => paintScoring(context, palette, scoring, y, s) });
  }
  if (card.wishlist !== null) {
    const wishlist = card.wishlist;
    // Without rated games, Deseados becomes the image's wall of covers.
    const wall = card.games === null;
    const layout = wishlistLayout(wishlist, wall);
    blocks.push({
      natural: layout.naturalHeight, naturalWidth: layout.naturalWidth,
      focus: (y, s) => y + layout.naturalHeight * s * 0.55,
      paint: (y, s) => paintWishlist(context, palette, images, wishlist, wall, y, s),
    });
  }
  if (blocks.length === 0) {
    blocks.push({ natural: INVITATION_HEIGHT, naturalWidth: 700, paint: (y, s) => paintInvitation(context, palette, y, s) });
  }

  // Lay the blocks out first, so the stage can gather its light behind the one that leads.
  const aliasTop = safe.top + (card.format === "story" ? 150 : 112);
  // Without blocks the alias is the whole message, so it takes a story's sizes in either format.
  const invitation = blocks.every((block) => block.focus === undefined) && card.scoring === null;
  const alias = fitAlias(context, card.alias, invitation ? "story" : card.format);
  const footerBottom = height - safe.bottom;
  const top = aliasBottom(alias, aliasTop) + 52;
  const available = footerBottom - FOOTER - 44 - top;
  const natural = blocks.reduce((sum, block) => sum + block.natural, 0) + GAP * (blocks.length - 1);
  const widest = Math.min(...blocks.map((block) => CONTENT / block.naturalWidth));
  const s = Math.max(0.5, Math.min(available / natural, safe.maxScale, widest));
  // Spare room centres a single block and otherwise spreads evenly around the blocks.
  const spare = Math.max(0, available - natural * s);
  const share = invitation ? 0 : blocks.length === 1 ? spare / 2 : spare / (blocks.length + 1);
  const placed: { block: Block; y: number }[] = [];
  let y = top + share;
  for (const block of blocks) {
    placed.push({ block, y });
    y += block.natural * s + GAP * s + share;
  }

  // The first game shown lights the stage; a product fallback never does.
  const leadCover = card.games?.items[0]?.cover ?? card.wishlist?.covers[0];
  const leadArt = leadCover?.provider ? images.get(leadCover.url) ?? null : null;
  const lead = placed.find(({ block }) => block.focus !== undefined);
  paintStage(context, palette, leadArt, height, lead?.block.focus?.(lead.y, s) ?? height * 0.45);
  paintHeader(context, assets, safe.top);
  paintAlias(context, palette, alias, aliasTop);
  paintFooter(context, palette, card, footerBottom);
  for (const { block, y: at } of placed) block.paint(at, s);
}

/**
 * Paints the card over the whole canvas once its images have loaded. Returns false when the
 * canvas has no 2D context or a newer paint replaced this one.
 */
export async function paintProfileCard(
  canvas: HTMLCanvasElement, card: ProfileCard, assets: CardAssets, isCurrent: () => boolean = () => true,
): Promise<boolean> {
  const { width, height } = SHARE_FORMATS[card.format];
  const context = canvas.getContext("2d");
  if (context === null) return false;
  // The card is drawn offscreen first, so a superseded paint never reaches the preview.
  const scratch = document.createElement("canvas");
  scratch.width = width;
  scratch.height = height;
  const draft = scratch.getContext("2d");
  if (draft === null) return false;
  draft.textBaseline = "alphabetic";
  await compose(draft, card, assets);
  if (!isCurrent()) return false;
  if (canvas.width !== width) canvas.width = width;
  if (canvas.height !== height) canvas.height = height;
  context.clearRect(0, 0, width, height);
  context.drawImage(scratch, 0, 0);
  return true;
}

/** The exported PNG of a painted card, or null when the browser cannot export it. */
export function exportProfileCard(canvas: HTMLCanvasElement): Promise<Blob | null> {
  return canvasToBlob(canvas);
}
