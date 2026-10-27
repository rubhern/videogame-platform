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
      <div className="page-container">
        <details className="platform-credits">
          <summary>Créditos de marcas</summary>
          <p>
            The Android robot is reproduced or modified from work created and shared by Google
            and used according to terms described in the{" "}
            <a href="https://creativecommons.org/licenses/by/3.0/">Creative Commons 3.0 Attribution License</a>.
            {" "}Android is a trademark of Google LLC.
          </p>
          <p>
            Tux: Larry Ewing, Garrett LeSage e IFo Hancroft.{" "}
            <a href="https://github.com/torvalds/linux/blob/master/Documentation/images/COPYING-logo">Permiso y autoría</a>.
            {" "}Linux® es una marca registrada de Linus Torvalds en Estados Unidos y otros países.
          </p>
          <p>
            Windows es una marca del grupo de empresas Microsoft. SteamVR es una marca de Valve Corporation.
            {" "}Las demás marcas pertenecen a sus respectivos titulares. No implican afiliación ni patrocinio.
          </p>
        </details>
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
