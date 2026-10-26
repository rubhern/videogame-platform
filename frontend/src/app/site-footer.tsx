import { BrandLogo } from "../shared/brand/brand-logo";
import { LegalLinks } from "../shared/legal/legal-links";

/** The shared footer (#283): identity and slogan, link columns, then copyright, data source and signature. */
export function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="site-footer-main page-container">
        <div className="site-footer-identity">
          <span className="site-footer-brand">
            <BrandLogo className="site-footer-logo" />
            <span className="sr-only">Gameómetro</span>
          </span>
          <p className="site-footer-slogan">
            <span>Un hombre elige.</span>
            <span>Un esclavo obedece.</span>
            <span className="site-footer-slogan-accent">Nosotros te ayudamos a elegir.</span>
          </p>
        </div>
        <LegalLinks />
      </div>
      <div className="site-footer-base">
        <div className="page-container">
          <p>© {new Date().getFullYear()} Gameómetro</p>
          <p>
            Datos de videojuegos: <a href="https://www.igdb.com/">IGDB</a>
          </p>
          <p className="site-footer-signature">
            Ideado, desarrollado y mantenido por <span>Rubén Hernández</span>
          </p>
        </div>
      </div>
    </footer>
  );
}
