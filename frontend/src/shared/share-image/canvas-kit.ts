/**
 * The drawing kit of Gameómetro's exported images: the operator's weekly carousel (#589) and a
 * player's public profile image (#619). Each image is painted on a 2D canvas, so the preview a
 * person reviews is the exact PNG that leaves the browser. Colours come from the global semantic
 * tokens at paint time. Provider images are requested with CORS (the IGDB CDN allows any origin),
 * so the canvas stays exportable; an image that cannot load that way is left out rather than
 * tainting the export.
 */

export const SANS = '"Mona Sans", ui-sans-serif, system-ui, sans-serif';
export const MONO = '"IBM Plex Mono", ui-monospace, monospace';

/** Mona Sans' width axis: the wide display cut, the condensed readout voice, or its regular width. */
export type FontWidth = "wide" | "condensed" | "normal";

const STRETCH: Record<FontWidth, string> = { wide: "semi-expanded", condensed: "condensed", normal: "normal" };

/** A global colour token such as `night`, read from the document so the image follows the site. */
export function readColor(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(`--color-${name}`).trim() || "#808080";
}

const images = new Map<string, Promise<HTMLImageElement | null>>();

export function loadImage(source: string, crossOrigin: boolean): Promise<HTMLImageElement | null> {
  const cached = images.get(source);
  if (cached) return cached;
  const loading = new Promise<HTMLImageElement | null>((resolve) => {
    const image = new Image();
    if (crossOrigin) image.crossOrigin = "anonymous";
    image.onload = () => resolve(image);
    image.onerror = () => resolve(null);
    image.src = source;
  });
  images.set(source, loading);
  return loading;
}

/** A product SVG with only a viewBox gets an intrinsic size, which every browser can draw. */
export function sizedSvg(source: string, width: number): string {
  const box = /viewBox="([\d.\s-]+)"/.exec(source)?.[1]?.trim().split(/\s+/).map(Number) ?? [0, 0, 1, 1];
  const height = Math.round((width * (box[3] ?? 1)) / (box[2] ?? 1));
  const sized = source.trim().replace("<svg ", `<svg width="${width}" height="${height}" `);
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(sized)}`;
}

export async function loadSvgFile(path: string): Promise<HTMLImageElement | null> {
  try {
    const response = await fetch(path);
    return response.ok ? await loadImage(sizedSvg(await response.text(), 512), false) : null;
  } catch {
    return null;
  }
}

/** Provider images load cross-origin; product assets are same-origin. */
export function loadArt(url: string): Promise<HTMLImageElement | null> {
  if (/^https?:/.test(url)) return loadImage(url, true);
  return url.endsWith(".svg") ? loadSvgFile(url) : loadImage(url, false);
}

/** Waits for the faces an image uses, so a paint never falls back to a system font. */
export async function loadFaces(samples: readonly string[]): Promise<void> {
  if (!("fonts" in document)) return;
  await Promise.allSettled(samples.map((sample) => document.fonts.load(sample)));
}

export function font(
  context: CanvasRenderingContext2D, weight: number, size: number, family = SANS, tracking = 0,
  width: FontWidth = "normal",
) {
  context.font = `${weight} ${size}px ${family}`;
  if ("fontStretch" in context) context.fontStretch = STRETCH[width] as CanvasFontStretch;
  if ("letterSpacing" in context) context.letterSpacing = `${tracking}px`;
}

export function wrap(context: CanvasRenderingContext2D, text: string, maxWidth: number): string[] {
  const lines: string[] = [];
  let line = "";
  for (const word of text.split(/\s+/).filter(Boolean)) {
    const candidate = line === "" ? word : `${line} ${word}`;
    if (line !== "" && context.measureText(candidate).width > maxWidth) {
      lines.push(line);
      line = word;
    } else {
      line = candidate;
    }
  }
  if (line !== "") lines.push(line);
  return lines;
}

/** At most `maxLines` lines; the last one that would overflow ends in an ellipsis. */
export function clampLines(context: CanvasRenderingContext2D, text: string, maxWidth: number, maxLines: number): string[] {
  const lines = wrap(context, text, maxWidth);
  if (lines.length <= maxLines && lines.every((line) => context.measureText(line).width <= maxWidth)) return lines;
  const kept = lines.slice(0, maxLines);
  let last = kept.at(-1) ?? "";
  if (lines.length > maxLines) last = `${last} ${lines.slice(maxLines).join(" ")}`;
  while (last.length > 1 && context.measureText(`${last}…`).width > maxWidth) last = last.slice(0, -1).trimEnd();
  kept[kept.length - 1] = `${last}…`;
  return kept;
}

/** The largest heavy size, down to `min`, at which the text fits in `maxLines` lines. */
export function fit(
  context: CanvasRenderingContext2D, text: string, max: number, min: number, maxWidth: number, maxLines: number,
  width: FontWidth = "normal",
) {
  for (let size = max; size >= min; size -= 4) {
    font(context, 800, size, SANS, -size * 0.02, width);
    const lines = wrap(context, text, maxWidth);
    if (lines.length <= maxLines && lines.every((line) => context.measureText(line).width <= maxWidth)) return { size, lines };
  }
  font(context, 800, min, SANS, -min * 0.02, width);
  return { size: min, lines: wrap(context, text, maxWidth) };
}

/** Fire to its light, left to right across the text: an image's warm accent. */
export function fireFill(context: CanvasRenderingContext2D, fire: string, fireLight: string, x: number, width: number) {
  const gradient = context.createLinearGradient(x, 0, x + Math.max(width, 1), 0);
  gradient.addColorStop(0, fire);
  gradient.addColorStop(1, fireLight);
  return gradient;
}

/** Ink to ice, top to bottom, as the mark's G is lit. */
export function iceFill(context: CanvasRenderingContext2D, ink: string, ice: string, top: number, bottom: number) {
  const gradient = context.createLinearGradient(0, top, 0, bottom);
  gradient.addColorStop(0, ink);
  gradient.addColorStop(1, ice);
  return gradient;
}

/** A soft light of one colour over the whole canvas, gathered at (x, y). */
export function glow(context: CanvasRenderingContext2D, colour: string, x: number, y: number, radius: number, alpha: number) {
  const gradient = context.createRadialGradient(x, y, 0, x, y, radius);
  gradient.addColorStop(0, colour);
  gradient.addColorStop(1, "transparent");
  context.save();
  context.globalAlpha = alpha;
  context.fillStyle = gradient;
  context.fillRect(0, 0, context.canvas.width, context.canvas.height);
  context.restore();
}

export function roundedPath(
  context: CanvasRenderingContext2D, x: number, y: number, width: number, height: number, radius: number | number[],
) {
  context.beginPath();
  context.roundRect(x, y, width, height, radius);
}

/** Crops an image to cover the box, weighted to its upper part as the site's frames are. */
export function drawCover(
  context: CanvasRenderingContext2D, image: HTMLImageElement, x: number, y: number, width: number, height: number,
  focusY = 0.3,
) {
  const scale = Math.max(width / image.naturalWidth, height / image.naturalHeight);
  const sourceWidth = width / scale;
  const sourceHeight = height / scale;
  const sourceX = (image.naturalWidth - sourceWidth) / 2;
  const sourceY = (image.naturalHeight - sourceHeight) * focusY;
  context.drawImage(image, sourceX, sourceY, sourceWidth, sourceHeight, x, y, width, height);
}

export function drawLogo(context: CanvasRenderingContext2D, logo: HTMLImageElement | null, x: number, y: number, width: number) {
  if (logo === null) return;
  context.save();
  context.shadowColor = "rgba(0, 0, 0, 0.55)";
  context.shadowBlur = 18;
  context.drawImage(logo, x, y, width, (width * logo.naturalHeight) / logo.naturalWidth);
  context.restore();
}

/** The PNG of a painted canvas, or null when the browser cannot export it. */
export function canvasToBlob(canvas: HTMLCanvasElement): Promise<Blob | null> {
  return new Promise((resolve) => {
    try {
      canvas.toBlob(resolve, "image/png");
    } catch {
      resolve(null);
    }
  });
}

/** Hands a file to the browser's own download. */
export function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

/** Whether the browser can hand PNG files to another app, as a phone's share sheet does. */
export function canShareImages(): boolean {
  try {
    return typeof navigator.canShare === "function" &&
      navigator.canShare({ files: [new File([""], "imagen.png", { type: "image/png" })] });
  } catch {
    return false;
  }
}
