import logoSource from "../../shared/brand/gameometro-logo.svg?raw";
import markSource from "../../shared/brand/gameometro-mark.svg?raw";
import { platformLogoAssets, type PlatformLogoName } from "../../shared/catalogue/platform-logo-assets";
import {
  canvasToBlob,
  drawCover,
  drawLogo,
  fireFill as kitFireFill,
  fit,
  font,
  glow,
  iceFill as kitIceFill,
  loadArt,
  loadFaces,
  loadImage,
  loadSvgFile,
  MONO,
  readColor,
  roundedPath,
  SANS,
  sizedSvg,
} from "../../shared/share-image/canvas-kit";

import {
  SLIDE_HEIGHT,
  SLIDE_WIDTH,
  type CarouselSlide,
  type ClosingSlide,
  type CoverSlide,
  type GameSlide,
  type SlideImage,
} from "./weekly-carousel-model";

/**
 * Paints a carousel slide on a 2D canvas with the shared drawing kit, so the preview the operator
 * reviews is the exact PNG that is exported.
 */

type Palette = {
  night: string; raised: string; ink: string; subtle: string;
  ice: string; iceLight: string; fire: string; fireLight: string;
};

/** Where a slide sits in the carousel, for its `2/8` counter. */
export type SlidePosition = { index: number; total: number };

const PAD = 72;

function readPalette(): Palette {
  return {
    night: readColor("night"), raised: readColor("raised"), ink: readColor("ink"), subtle: readColor("subtle"),
    ice: readColor("ice"), iceLight: readColor("ice-light"), fire: readColor("fire"), fireLight: readColor("fire-light"),
  };
}

function loadSlideImage(image: SlideImage): Promise<HTMLImageElement | null> {
  return loadArt(image.url);
}

function loadPlatformLogo(name: PlatformLogoName): Promise<HTMLImageElement | null> {
  const file = platformLogoAssets[name].file;
  if (file === null) return Promise.resolve(null);
  const path = `/assets/platforms/${file}`;
  return file.endsWith(".svg") ? loadSvgFile(path) : loadImage(path, false);
}

export type SlideAssets = { palette: Palette; logo: HTMLImageElement | null; mark: HTMLImageElement | null };

/** Waits for the faces and brand art every slide shares, so a paint never falls back. */
export async function loadSlideAssets(): Promise<SlideAssets> {
  await loadFaces([`800 96px ${SANS}`, `500 40px ${SANS}`, `400 30px ${MONO}`]);
  const [logo, mark] = await Promise.all([
    loadImage(sizedSvg(logoSource, 1140), false),
    loadImage(sizedSvg(markSource, 512), false),
  ]);
  return { palette: readPalette(), logo, mark };
}

// ---------------------------------------------------------------- Drawing primitives

/** Fire to its light, left to right across the text: the slide's warm accent. */
function fireFill(context: CanvasRenderingContext2D, palette: Palette, x: number, width: number) {
  return kitFireFill(context, palette.fire, palette.fireLight, x, width);
}

/** Ink to ice, top to bottom, as the mark's G is lit. */
function iceFill(context: CanvasRenderingContext2D, palette: Palette, top: number, bottom: number) {
  return kitIceFill(context, palette.ink, palette.ice, top, bottom);
}

/** Night, with ice light gathering at the top right and fire's last warmth at the bottom left. */
function paintStage(context: CanvasRenderingContext2D, palette: Palette) {
  context.fillStyle = palette.night;
  context.fillRect(0, 0, SLIDE_WIDTH, SLIDE_HEIGHT);
  glow(context, palette.ice, SLIDE_WIDTH, 0, 620, 0.16);
  glow(context, palette.fire, 0, SLIDE_HEIGHT, 520, 0.12);
}

/** `2/8` in the monospaced readout; over art it sits on a small night plate. */
function drawCounter(context: CanvasRenderingContext2D, palette: Palette, position: SlidePosition, plate: boolean) {
  const text = `${position.index + 1}/${position.total}`;
  font(context, 400, 30, MONO, 1);
  const width = context.measureText(text).width;
  const right = SLIDE_WIDTH - PAD;
  if (plate) {
    context.save();
    context.globalAlpha = 0.78;
    context.fillStyle = palette.night;
    roundedPath(context, right - width - 32, 56, width + 32, 48, 10);
    context.fill();
    context.restore();
  }
  context.fillStyle = plate ? palette.ink : palette.subtle;
  context.textAlign = "right";
  context.fillText(text, right - (plate ? 16 : 0), 91);
  context.textAlign = "left";
}

// ---------------------------------------------------------------- Cover

async function paintCover(context: CanvasRenderingContext2D, slide: CoverSlide, assets: SlideAssets, position: SlidePosition) {
  const { palette } = assets;
  const covers = await Promise.all(slide.covers.map(loadSlideImage));
  paintStage(context, palette);
  drawLogo(context, assets.logo, PAD, 68, 248);
  drawCounter(context, palette, position, false);

  // `Lanzamientos` over `de la semana`, the lit word in fire.
  const [first = "", ...rest] = slide.heading.lead.split(" ");
  font(context, 800, 104, SANS, -2);
  context.fillStyle = palette.ink;
  context.fillText(first, PAD, 310);
  const leadText = `${rest.join(" ")} `;
  context.fillText(leadText, PAD, 404);
  const accentX = PAD + context.measureText(leadText).width;
  context.fillStyle = fireFill(context, palette, accentX, context.measureText(slide.heading.accent).width);
  context.fillText(slide.heading.accent, accentX, 404);

  font(context, 700, 60, SANS, -1);
  context.fillStyle = iceFill(context, palette, 440, 500);
  context.fillText(slide.range, PAD, 494);

  paintCoverGrid(context, covers, palette);
}

/** Up to six covers in rows of three, each row centred, the middle column raised a little. */
function paintCoverGrid(context: CanvasRenderingContext2D, covers: (HTMLImageElement | null)[], palette: Palette) {
  const width = 250;
  const height = 334;
  const gap = 28;
  const rows = [covers.slice(0, 3), covers.slice(3, 6)].filter((row) => row.length > 0);
  const top = rows.length === 1 ? 700 : 610;
  rows.forEach((row, rowIndex) => {
    const rowWidth = row.length * width + (row.length - 1) * gap;
    const left = (SLIDE_WIDTH - rowWidth) / 2;
    row.forEach((image, column) => {
      const x = left + column * (width + gap);
      const y = top + rowIndex * (height + gap) - (row.length === 3 && column === 1 ? 24 : 0);
      context.save();
      context.shadowColor = "rgba(0, 0, 0, 0.6)";
      context.shadowBlur = 40;
      context.shadowOffsetY = 18;
      context.fillStyle = palette.raised;
      roundedPath(context, x, y, width, height, 8);
      context.fill();
      context.restore();
      context.save();
      roundedPath(context, x, y, width, height, 8);
      context.clip();
      if (image !== null) drawCover(context, image, x, y, width, height, 0.5);
      context.strokeStyle = "rgba(255, 255, 255, 0.08)";
      context.lineWidth = 2;
      context.stroke();
      context.restore();
    });
  });
}

// ---------------------------------------------------------------- Game

/** The art frame: landscape art fills it; a cover stands whole over its own blurred light. */
function paintArt(context: CanvasRenderingContext2D, image: HTMLImageElement | null, art: SlideImage, height: number, palette: Palette) {
  if (image !== null) {
    context.save();
    if (art.fit === "contain") {
      context.filter = "blur(48px) brightness(0.55)";
      drawCover(context, image, -80, -80, SLIDE_WIDTH + 160, height + 160, 0.5);
      context.filter = "none";
      const coverHeight = height - 150;
      const coverWidth = (coverHeight * image.naturalWidth) / image.naturalHeight;
      context.shadowColor = "rgba(0, 0, 0, 0.6)";
      context.shadowBlur = 48;
      context.drawImage(image, (SLIDE_WIDTH - coverWidth) / 2, 110, coverWidth, coverHeight);
    } else {
      drawCover(context, image, 0, 0, SLIDE_WIDTH, height, 0.3);
    }
    context.restore();
  }
  // The art dissolves into night where the reading begins.
  const shade = context.createLinearGradient(0, height * 0.55, 0, height);
  shade.addColorStop(0, "transparent");
  shade.addColorStop(1, palette.night);
  context.fillStyle = shade;
  context.fillRect(0, 0, SLIDE_WIDTH, height + 2);
  context.fillStyle = palette.night;
  context.fillRect(0, height, SLIDE_WIDTH, SLIDE_HEIGHT - height);
  glow(context, palette.fire, 0, SLIDE_HEIGHT, 520, 0.12);
  // A quiet top shade keeps the lockup and counter legible over bright art.
  const top = context.createLinearGradient(0, 0, 0, 200);
  top.addColorStop(0, "rgba(0, 0, 0, 0.35)");
  top.addColorStop(1, "transparent");
  context.fillStyle = top;
  context.fillRect(0, 0, SLIDE_WIDTH, 200);
}

/** Platform marks drawn in ink at one height; a platform without one keeps its name. */
async function paintPlatforms(context: CanvasRenderingContext2D, slide: GameSlide, palette: Palette, y: number) {
  const logos = await Promise.all(slide.platforms.map((platform) => platform.logo === null ? null : loadPlatformLogo(platform.logo)));
  const height = 44;
  let x = PAD;
  slide.platforms.forEach((platform, index) => {
    const image = logos[index] ?? null;
    if (x > SLIDE_WIDTH - PAD - 60) return;
    if (image === null) {
      font(context, 600, 34);
      context.fillStyle = palette.ink;
      context.fillText(platform.name, x, y + 34);
      x += context.measureText(platform.name).width + 40;
      return;
    }
    const width = Math.min((height * image.naturalWidth) / image.naturalHeight, 150);
    const tinted = document.createElement("canvas");
    tinted.width = Math.ceil(width * 2);
    tinted.height = height * 2;
    const tint = tinted.getContext("2d");
    if (tint === null) return;
    tint.drawImage(image, 0, 0, tinted.width, tinted.height);
    tint.globalCompositeOperation = "source-in";
    tint.fillStyle = palette.ink;
    tint.fillRect(0, 0, tinted.width, tinted.height);
    context.drawImage(tinted, x, y, width, height);
    x += width + 40;
  });
}

async function paintGame(context: CanvasRenderingContext2D, slide: GameSlide, assets: SlideAssets, position: SlidePosition) {
  const { palette } = assets;
  const art = await loadSlideImage(slide.art);
  const artHeight = slide.featured ? 760 : 720;
  paintStage(context, palette);
  paintArt(context, art, slide.art, artHeight, palette);
  drawLogo(context, assets.logo, PAD, 58, 228);
  drawCounter(context, palette, position, true);

  let y = artHeight - (slide.featured ? 40 : 10);
  if (slide.featured) {
    font(context, 400, 30, MONO, 7);
    const kicker = "★ LANZAMIENTO DE LA SEMANA";
    context.fillStyle = fireFill(context, palette, PAD, context.measureText(kicker).width);
    context.fillText(kicker, PAD, y);
    y += 30;
  }

  if (slide.day !== null) {
    font(context, 800, 150, SANS, -6);
    const top = y + 14;
    context.fillStyle = iceFill(context, palette, top, top + 120);
    context.fillText(slide.day.day, PAD - 4, top + 108);
    const dayWidth = context.measureText(slide.day.day).width;
    font(context, 400, 34, MONO, 5);
    context.fillStyle = palette.ink;
    context.fillText(slide.day.weekday, PAD + dayWidth + 28, top + 42);
    context.fillText(slide.day.monthYear, PAD + dayWidth + 28, top + 92);
    y = top + 120;
  } else {
    font(context, 800, 64, SANS, -1);
    context.fillStyle = iceFill(context, palette, y + 20, y + 80);
    context.fillText(slide.date, PAD, y + 76, SLIDE_WIDTH - PAD * 2);
    y += 96;
  }

  const title = fit(context, slide.title, 84, 52, SLIDE_WIDTH - PAD * 2, 2);
  context.fillStyle = palette.ink;
  const firstBaseline = y + 56 + title.size * 0.8;
  title.lines.forEach((line, index) => context.fillText(line, PAD, firstBaseline + index * title.size * 1.04));
  y = firstBaseline + (title.lines.length - 1) * title.size * 1.04;

  if (slide.platforms.length > 0) {
    await paintPlatforms(context, slide, palette, y + 46);
    y += 46 + 44;
  }
  if (slide.genres.length > 0) {
    font(context, 500, 40);
    context.fillStyle = palette.iceLight;
    context.fillText(slide.genres.join(" · "), PAD, y + 82, SLIDE_WIDTH - PAD * 2);
  }
}

// ---------------------------------------------------------------- Closing

function centred(context: CanvasRenderingContext2D, text: string, y: number) {
  context.textAlign = "center";
  context.fillText(text, SLIDE_WIDTH / 2, y);
  context.textAlign = "left";
}

function paintClosing(context: CanvasRenderingContext2D, slide: ClosingSlide, assets: SlideAssets) {
  const { palette } = assets;
  paintStage(context, palette);
  if (assets.mark !== null) context.drawImage(assets.mark, (SLIDE_WIDTH - 150) / 2, 318, 150, 150);

  // `¿Cuál vas` over `a jugar?`, the lit word in fire.
  font(context, 800, 84, SANS, -2);
  context.fillStyle = palette.ink;
  const words = slide.question.lead.trim().split(" ");
  centred(context, words.slice(0, -1).join(" "), 614);
  const parts = [`${words.at(-1) ?? ""} `, slide.question.accent, slide.question.end];
  const widths = parts.map((part) => context.measureText(part).width);
  let x = (SLIDE_WIDTH - widths.reduce((sum, width) => sum + width, 0)) / 2;
  parts.forEach((part, index) => {
    context.fillStyle = index === 1 ? fireFill(context, palette, x, widths[1] ?? 0) : palette.ink;
    context.fillText(part, x, 696);
    x += widths[index] ?? 0;
  });

  font(context, 500, 38);
  context.fillStyle = palette.iceLight;
  slide.lines.forEach((line, index) => centred(context, line, 778 + index * 50));

  font(context, 700, 54, SANS, -1);
  const hostWidth = context.measureText(slide.host).width;
  const ruleWidth = 56;
  const start = (SLIDE_WIDTH - (ruleWidth + 24 + hostWidth)) / 2;
  context.fillStyle = fireFill(context, palette, start, ruleWidth);
  context.fillRect(start, 942, ruleWidth, 5);
  context.fillStyle = palette.ink;
  context.fillText(slide.host, start + ruleWidth + 24, 965);

  font(context, 400, 28, MONO, 7);
  const cue = slide.cue.toUpperCase();
  const cueWidth = context.measureText(cue).width;
  context.fillStyle = fireFill(context, palette, (SLIDE_WIDTH - cueWidth) / 2, cueWidth);
  centred(context, cue, 1038);

  font(context, 400, 24, MONO, 1);
  context.fillStyle = palette.subtle;
  centred(context, slide.credit, 1290);
}

// ---------------------------------------------------------------- Entry points

/**
 * Paints one slide over the whole 1080×1350 canvas once its images have loaded. Returns false when
 * the canvas has no 2D context or a newer paint replaced this one.
 */
export async function paintSlide(
  canvas: HTMLCanvasElement, slide: CarouselSlide, assets: SlideAssets, position: SlidePosition,
  isCurrent: () => boolean = () => true,
): Promise<boolean> {
  const context = canvas.getContext("2d");
  if (context === null) return false;
  // The slide is drawn offscreen first, so a superseded paint never reaches the preview.
  const scratch = document.createElement("canvas");
  scratch.width = SLIDE_WIDTH;
  scratch.height = SLIDE_HEIGHT;
  const draft = scratch.getContext("2d");
  if (draft === null) return false;
  draft.textBaseline = "alphabetic";
  if (slide.kind === "cover") await paintCover(draft, slide, assets, position);
  else if (slide.kind === "game") await paintGame(draft, slide, assets, position);
  else paintClosing(draft, slide, assets);
  if (!isCurrent()) return false;
  context.clearRect(0, 0, SLIDE_WIDTH, SLIDE_HEIGHT);
  context.drawImage(scratch, 0, 0);
  return true;
}

export function slideToBlob(canvas: HTMLCanvasElement): Promise<Blob | null> {
  return canvasToBlob(canvas);
}
