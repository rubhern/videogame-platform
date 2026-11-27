import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";

import type { FeaturedReleasesResponse } from "../releases/featured-releases-api";
import {
  toFeaturedReleasesViewModel,
  type FeaturedReleasesViewModel,
} from "../releases/featured-releases-view-model";
import type { ReleasesApiError } from "../releases/releases-api";
import { featuredReleasesQueryOptions } from "../releases/use-featured-releases-query";
import { canShareImages, saveBlob } from "../../shared/share-image/canvas-kit";
import { loadSlideAssets, paintSlide, slideToBlob, type SlideAssets } from "./slide-painter";
import {
  describeSlide,
  SLIDE_HEIGHT,
  SLIDE_WIDTH,
  toWeeklyCarousel,
  type CarouselSlide,
  type WeeklyCarousel,
} from "./weekly-carousel-model";

/** The ISO week the URL asks for; anything else is the current week, as Destacados reads it. */
const WEEK = /^(?!0000)\d{4}-W(0[1-9]|[1-4]\d|5[0-3])$/;
const WEEK_PARAM = "week";

function fileName(stem: string, index: number): string {
  return `${stem}-${String(index + 1).padStart(2, "0")}.png`;
}

function SlideCanvas({ slide, index, total, assets, register, onPainted }: {
  slide: CarouselSlide;
  index: number;
  total: number;
  assets: SlideAssets | null;
  register: (canvas: HTMLCanvasElement | null) => void;
  onPainted: (index: number) => void;
}) {
  const canvas = useRef<HTMLCanvasElement | null>(null);
  // Painting synchronizes React's slide with the canvas, an external drawing surface; its images
  // load first, and a paint superseded by a newer slide never reaches the canvas.
  useEffect(() => {
    let current = true;
    if (canvas.current !== null && assets !== null) {
      void paintSlide(canvas.current, slide, assets, { index, total }, () => current)
        .then((painted) => { if (painted && current) onPainted(index); });
    }
    return () => { current = false; };
  }, [slide, index, total, assets, onPainted]);
  return <canvas className="social-slide-canvas" width={SLIDE_WIDTH} height={SLIDE_HEIGHT}
    role="img" aria-label={describeSlide(slide)}
    ref={(element) => { canvas.current = element; register(element); }} />;
}

function CarouselPreview({ carousel }: { carousel: Extract<WeeklyCarousel, { status: "ready" }> }) {
  const [assets, setAssets] = useState<SlideAssets | null>(null);
  const [shareable] = useState(canShareImages);
  const [outcome, setOutcome] = useState("");
  const [painted, setPainted] = useState<ReadonlySet<number>>(() => new Set());
  const canvases = useRef<(HTMLCanvasElement | null)[]>([]);
  const markPainted = useCallback((index: number) => {
    setPainted((current) => current.has(index) ? current : new Set(current).add(index));
  }, []);

  useEffect(() => {
    let active = true;
    void loadSlideAssets().then((loaded) => { if (active) setAssets(loaded); });
    return () => { active = false; };
  }, []);

  async function slideFiles(): Promise<File[] | null> {
    const blobs = await Promise.all(carousel.slides.map((_, index) => {
      const canvas = canvases.current[index];
      return canvas ? slideToBlob(canvas) : Promise.resolve(null);
    }));
    if (blobs.some((blob) => blob === null)) return null;
    return blobs.map((blob, index) => new File([blob as Blob], fileName(carousel.fileStem, index), { type: "image/png" }));
  }

  async function downloadOne(index: number) {
    const canvas = canvases.current[index];
    const blob = canvas ? await slideToBlob(canvas) : null;
    if (blob === null) {
      setOutcome("No se pudo generar la imagen. Recarga la página e inténtalo de nuevo.");
      return;
    }
    saveBlob(blob, fileName(carousel.fileStem, index));
    setOutcome(`Imagen ${index + 1} descargada.`);
  }

  async function downloadAll() {
    const files = await slideFiles();
    if (files === null) {
      setOutcome("No se pudieron generar las imágenes. Recarga la página e inténtalo de nuevo.");
      return;
    }
    files.forEach((file) => saveBlob(file, file.name));
    setOutcome(`${files.length} imágenes descargadas.`);
  }

  async function share() {
    const files = await slideFiles();
    if (files === null) {
      setOutcome("No se pudieron generar las imágenes. Recarga la página e inténtalo de nuevo.");
      return;
    }
    try {
      await navigator.share({ files, text: carousel.caption });
      setOutcome("Imágenes enviadas a la aplicación elegida.");
    } catch (error) {
      setOutcome(error instanceof DOMException && error.name === "AbortError"
        ? "" : "No se pudieron compartir. Descárgalas y súbelas a mano.");
    }
  }

  async function copyCaption() {
    try {
      await navigator.clipboard.writeText(carousel.caption);
      setOutcome("Texto copiado.");
    } catch {
      setOutcome("No se pudo copiar: selecciona el texto y cópialo a mano.");
    }
  }

  const ready = painted.size === carousel.slides.length;
  return <>
    <div className="admin-control-actions">
      <button className="button button-primary" type="button" disabled={!ready}
        onClick={() => { void downloadAll(); }}>Descargar todas</button>
      {shareable ? <button className="button" type="button" disabled={!ready}
        onClick={() => { void share(); }}>Compartir imágenes</button> : null}
      <p className="admin-quiet" role="status">{ready ? outcome : "Preparando las imágenes…"}</p>
    </div>
    <ol className="social-slides" aria-label={`Diapositivas del carrusel (${carousel.slides.length})`}>
      {carousel.slides.map((slide, index) => <li className="social-slide" key={`${carousel.week}-${index}`}>
        <SlideCanvas slide={slide} index={index} total={carousel.slides.length} assets={assets}
          onPainted={markPainted} register={(canvas) => { canvases.current[index] = canvas; }} />
        <div className="social-slide-foot">
          <span>{index + 1} de {carousel.slides.length}</span>
          <button className="button" type="button" disabled={!ready}
            aria-label={`Descargar diapositiva ${index + 1}`}
            onClick={() => { void downloadOne(index); }}>Descargar</button>
        </div>
      </li>)}
    </ol>
    <div className="admin-field">
      <label htmlFor="social-caption">Texto de la publicación</label>
      <textarea id="social-caption" className="admin-prompt" readOnly rows={14} value={carousel.caption} />
    </div>
    <div className="admin-control-actions">
      <button className="button" type="button" onClick={() => { void copyCaption(); }}>Copiar texto</button>
    </div>
  </>;
}

function WeekControls({ featured, onSelect }: {
  featured: FeaturedReleasesViewModel | undefined;
  onSelect: (week: string | null) => void;
}) {
  return <div className="admin-control-actions social-week">
    <button className="button" type="button" disabled={!featured?.previous}
      onClick={() => { if (featured?.previous) onSelect(featured.previous.period); }}>Semana anterior</button>
    <button className="button" type="button" disabled={featured === undefined || featured.isCurrent}
      onClick={() => onSelect(null)}>Semana actual</button>
    <button className="button" type="button" disabled={!featured?.next}
      onClick={() => { if (featured?.next) onSelect(featured.next.period); }}>Semana siguiente</button>
  </div>;
}

/**
 * The weekly social carousel (#589): the week's Destacados selection as 1080×1350 PNG slides and a
 * ready-to-paste caption. It reads the same public featured-releases response as the site, so no
 * provider is called, and publishing stays a manual step outside Gameómetro.
 */
export function SocialCarousel() {
  const [params, setParams] = useSearchParams();
  const requested = params.get(WEEK_PARAM);
  const week = requested !== null && WEEK.test(requested) ? requested : null;
  const featured = useQuery<FeaturedReleasesResponse, ReleasesApiError, FeaturedReleasesViewModel>({
    ...featuredReleasesQueryOptions({ granularity: "week", period: week }),
    select: toFeaturedReleasesViewModel,
    placeholderData: keepPreviousData,
  });
  // A stable carousel per response keeps the slides from being painted again on every render.
  const carousel = useMemo(
    () => (featured.data === undefined ? null : toWeeklyCarousel(featured.data)), [featured.data]);

  function selectWeek(next: string | null) {
    setParams((current) => {
      const updated = new URLSearchParams(current);
      if (next === null) updated.delete(WEEK_PARAM);
      else updated.set(WEEK_PARAM, next);
      return updated;
    });
  }

  return <section className="admin-section" aria-labelledby="social-title">
    <h2 id="social-title">Carrusel semanal</h2>
    <p className="admin-lead">
      Las imágenes y el texto salen de la misma selección automática que Destacados, con las portadas
      y artes de IGDB. Descárgalas, o compártelas desde el móvil, y publícalas a mano en las redes de
      Gameómetro con el texto copiado.
    </p>
    <WeekControls featured={featured.data} onSelect={selectWeek} />
    {carousel !== null ? <p className="admin-quiet">
      Semana {carousel.range} ({carousel.week}){featured.isFetching ? " · Actualizando…" : ""}
    </p> : null}
    {featured.isPending ? <p className="result-count" role="status">Cargando los destacados de la semana…</p>
      : featured.isError ? <div className="notice notice-danger" role="alert">
        <p className="notice-kicker">Error de carga</p>
        <h3>No se pudieron leer los destacados de la semana</h3>
        <p>{featured.error.status === 422 ? "Esa semana queda fuera de las que ofrece Destacados." : "Inténtalo de nuevo en unos momentos."}</p>
        <div className="admin-control-actions">
          <button className="button" type="button" onClick={() => { void featured.refetch(); }}>Reintentar</button>
          {week !== null ? <button className="button" type="button" onClick={() => selectWeek(null)}>Ir a la semana actual</button> : null}
        </div>
        {featured.error.correlationId ? <p className="notice-reference">Referencia para soporte: {featured.error.correlationId}</p> : null}
      </div>
        : carousel?.status === "empty" ? <div className="notice notice-empty" role="status">
          <p className="notice-kicker">Sin carrusel</p>
          <h3>No hay lanzamientos destacados esta semana</h3>
          <p>Destacados no tiene juegos para {carousel.range}, así que no se genera ninguna imagen.</p>
        </div>
          : carousel?.status === "ready" ? <CarouselPreview key={carousel.week} carousel={carousel} /> : null}
  </section>;
}
