import { Link } from "react-router-dom";


type DocumentKey = "privacidad" | "condiciones" | "aviso-legal" | "cookies";
// The footer's reading order (#283), not the routes' declaration order.
const documents: ReadonlyArray<readonly [DocumentKey, string]> = [
  ["aviso-legal", "Aviso legal"], ["privacidad", "Privacidad"], ["cookies", "Cookies"], ["condiciones", "Condiciones de uso"],
];

/** Shared footer destinations, available in every application build. */
export function LegalLinks() {
  return <div className="site-footer-links">
    <nav className="site-footer-column" aria-labelledby="site-footer-product">
      <p className="site-footer-heading" id="site-footer-product">Gameómetro</p>
      <ul>
        <li><Link to="/sobre-gameometro">Sobre Gameómetro</Link></li>
        <li><a href="mailto:hola@gameometro.com">Contacto</a></li>
      </ul>
    </nav>
    <nav className="site-footer-column" aria-labelledby="site-footer-legal">
      <p className="site-footer-heading" id="site-footer-legal">Legal</p>
      <ul>
        {documents.map(([key, label]) => <li key={key}><Link to={`/${key}`}>{label}</Link></li>)}
      </ul>
    </nav>
  </div>;
}
