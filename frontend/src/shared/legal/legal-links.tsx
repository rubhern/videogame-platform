import { Link } from "react-router-dom";

import { legalPublicationApproved } from "./publication";

type DocumentKey = "privacidad" | "condiciones" | "aviso-legal" | "cookies";
const labels: Record<DocumentKey, string> = {
  privacidad: "Privacidad", condiciones: "Condiciones de uso", "aviso-legal": "Aviso legal", cookies: "Cookies",
};


export function LegalLinks({ review = import.meta.env.DEV }: { review?: boolean }) {
  if (!legalPublicationApproved && !review) return null;
  return <nav className="legal-links" aria-label="Información legal">
    {(Object.keys(labels) as DocumentKey[]).map((key) => <Link key={key} to={`/${key}`}>{labels[key]}</Link>)}
    <a href="mailto:hola@gameometro.com">Contacto</a>
    {!legalPublicationApproved && <span>Borradores para revisión</span>}
  </nav>;
}
