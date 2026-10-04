import { useId, useLayoutEffect, useRef, useState } from "react";

import type { GameDetails } from "./game-details-api";

const PAGE_LANGUAGE = "es";

/** The Spanish name of a summary's language, or null when the tag cannot be named. */
function languageName(tag: string): string | null {
  try {
    return new Intl.DisplayNames([PAGE_LANGUAGE], { type: "language" }).of(tag) ?? null;
  } catch {
    return null;
  }
}

/**
 * Resumen: editorial text below the title, compact until expanded. The text keeps its language for
 * assistive technology, and a sourced summary names its source and, when it is not Spanish, the
 * language it is in; product-derived text explicitly credits its translation. The catalogue's own
 * editorial text needs no credit.
 */
export function GameSummary({ summary }: { summary: GameDetails["summary"] }) {
  const textId = useId();
  const text = useRef<HTMLParagraphElement>(null);
  const [expanded, setExpanded] = useState(false);
  const [overflows, setOverflows] = useState(false);

  // Only a summary taller than its compact frame offers to expand, so the action never expands
  // nothing. The observer reports the frame as soon as it observes it and again whenever the
  // collapsed frame changes width.
  useLayoutEffect(() => {
    const element = text.current;
    if (element === null || expanded || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(() =>
      setOverflows(element.scrollHeight > element.clientHeight + 1),
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, [expanded, summary.text]);

  const language =
    summary.language.toLowerCase().startsWith(PAGE_LANGUAGE) ? null : languageName(summary.language);

  return (
    <section className="game-summary" aria-labelledby="game-summary-title">
      <h2 className="sr-only" id="game-summary-title">
        Resumen
      </h2>
      <p
        className="game-summary-text"
        data-expanded={expanded}
        id={textId}
        lang={summary.language}
        ref={text}
      >
        {summary.text}
      </p>
      {overflows || expanded ? (
        <button
          aria-controls={textId}
          aria-expanded={expanded}
          className="game-summary-toggle"
          onClick={() => setExpanded((value) => !value)}
          type="button"
        >
          {expanded ? "Mostrar menos" : "Leer más"}
        </button>
      ) : null}
      {"provenance" in summary ? (
        <p className="game-panel-source">
          {"translation" in summary && summary.translation ? (
            <>
              Traducción automática de Gameómetro · Fuente del original: {summary.provenance.sourceName}
              {summary.translation.current ? null : " · Traducción pendiente de actualizar"}
            </>
          ) : (
            <>
              {language === null ? null : `Texto original en ${language} · `}
              Fuente: {summary.provenance.sourceName}
            </>
          )}
        </p>
      ) : null}
    </section>
  );
}
