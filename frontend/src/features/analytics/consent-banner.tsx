import { useEffect, useId, useRef } from "react";
import { Link } from "react-router-dom";

import type { ConsentChoice, ConsentState } from "./consent";

/**
 * The analytics consent notice (#507). It sits in the page flow above the header, so it never
 * covers content or traps focus, and the catalogue stays usable while it is open. Rejecting is
 * exactly as easy and as visible as accepting, and nothing is preselected.
 */
export function ConsentBanner({
  choice,
  reviewing,
  onChoose,
}: {
  choice: ConsentState;
  reviewing: boolean;
  onChoose: (choice: ConsentChoice) => void;
}) {
  const titleId = useId();
  const title = useRef<HTMLParagraphElement>(null);

  // Reopened from the footer: bring the notice into view and onto the keyboard.
  useEffect(() => {
    if (!reviewing) return;
    title.current?.scrollIntoView?.({ block: "nearest" });
    title.current?.focus({ preventScroll: true });
  }, [reviewing]);

  function choose(next: ConsentChoice) {
    onChoose(next);
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
            Si aceptas, usaremos Google Analytics con cookies para saber, de forma agregada, qué
            páginas y funciones se usan. No enviamos tu email, tus puntuaciones ni lo que buscas, y
            no hay publicidad. Si rechazas, no se carga nada de Google. Puedes cambiar de opinión
            cuando quieras en «Configurar cookies», al pie de la página.{" "}
            <Link to="/cookies">Más información</Link>
          </p>
          {reviewing && choice !== "unset" ? (
            <p className="consent-banner-current">
              Ahora mismo: {choice === "granted" ? "aceptadas" : "rechazadas"}.
            </p>
          ) : null}
        </div>
        <div className="consent-banner-actions">
          <button className="button" onClick={() => choose("denied")} type="button">
            Rechazar
          </button>
          <button className="button" onClick={() => choose("granted")} type="button">
            Aceptar
          </button>
        </div>
      </div>
    </section>
  );
}
