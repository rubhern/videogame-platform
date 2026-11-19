import { useEffect, useId, useRef, useState } from "react";
import { Link } from "react-router-dom";

import type { ConsentChoice, ConsentState } from "./consent";

export function ConsentBanner({
  analytics,
  ranking,
  reviewing,
  onChoose,
}: {
  analytics: ConsentState | null;
  ranking: ConsentState;
  reviewing: boolean;
  onChoose: (analytics: ConsentChoice, ranking: ConsentChoice) => void;
}) {
  const titleId = useId();
  const title = useRef<HTMLParagraphElement>(null);
  const [analyticsAllowed, setAnalyticsAllowed] = useState(analytics === "granted");
  const [rankingAllowed, setRankingAllowed] = useState(ranking === "granted");

  useEffect(() => {
    if (!reviewing) return;
    title.current?.scrollIntoView?.({ block: "nearest" });
    title.current?.focus({ preventScroll: true });
  }, [reviewing]);

  function choose(nextAnalytics: ConsentChoice, nextRanking: ConsentChoice) {
    onChoose(nextAnalytics, nextRanking);
    document.getElementById("main-content")?.focus({ preventScroll: true });
  }

  return (
    <section aria-labelledby={titleId} className="consent-banner">
      <div className="consent-banner-layout page-container">
        <div className="consent-banner-copy">
          <p className="consent-banner-title" id={titleId} ref={title} tabIndex={-1}>
            ¿Nos ayudas a mejorar Gameómetro?
          </p>
          <p>
            {analytics === null
              ? "Puedes aportar tus visitas al ranking público de juegos."
              : "Puedes permitir las cookies de Google Analytics y aportar tus visitas al ranking público de juegos."}
          </p>
          <details className="consent-preferences" open={reviewing || undefined}>
            <summary>Personalizar</summary>
            {analytics === null ? null : (
              <label>
                <input checked={analyticsAllowed} onChange={(event) => setAnalyticsAllowed(event.target.checked)} type="checkbox" />
                Google Analytics · uso de la web
              </label>
            )}
            <label>
              <input checked={rankingAllowed} onChange={(event) => setRankingAllowed(event.target.checked)} type="checkbox" />
              Ranking de visitas · sin cookies adicionales
            </label>
            <p>Guardamos cada visita al juego durante 8 días. Puedes cambiar tu elección en el pie de página.</p>
            <button
              className="button"
              onClick={() => choose(analyticsAllowed ? "granted" : "denied", rankingAllowed ? "granted" : "denied")}
              type="button"
            >
              Guardar preferencias
            </button>{" "}
            <Link to="/cookies">Más información</Link>
          </details>
        </div>
        <div className="consent-banner-actions">
          <button className="button" onClick={() => choose("denied", "denied")} type="button">
            Rechazar todo
          </button>
          <button className="button" onClick={() => choose("granted", "granted")} type="button">
            Aceptar todo
          </button>
        </div>
      </div>
    </section>
  );
}
