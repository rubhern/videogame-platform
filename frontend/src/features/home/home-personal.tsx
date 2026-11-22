import type { ReactNode } from "react";
import { Link, useLocation } from "react-router-dom";

import { thermalBand } from "../../shared/score/thermal-band";
import {
  LibraryIcon, PlatformsIcon, ProfileIcon, RatingsIcon, StatisticsIcon, WishlistIcon,
} from "../session/account-icons";
import { authenticationStartUrl } from "../session/auth-entry";
import { useSession } from "../session/use-session";

/** The rating scale the keypad uses, from ice to fire; each value takes its own temperature. */
const SCALE = Array.from({ length: 10 }, (_, index) => index + 1);

/** What a player account adds today; every entry names an implemented feature. */
const FEATURES = [
  {
    title: "Puntúa tus juegos",
    text: "Del 1 al 10 desde la ficha de cada juego, y por plataforma si quieres.",
  },
  { title: "Mis juegos", text: "Pendiente, Jugando, Completado o Abandonado: dónde está cada juego." },
  { title: "Mis deseados", text: "Los juegos que esperas, en el orden que tú decidas." },
  { title: "Mis puntuaciones", text: "Tu colección, para buscar, ordenar y editar cuando quieras." },
  { title: "Tus estadísticas", text: "Cómo puntúas, tus géneros y dónde has jugado." },
  { title: "Mis plataformas", text: "Destacados, Explorar y el calendario, centrados en lo tuyo." },
] as const;

/** The player's own destinations, as the account menu lists them; each page checks the session. */
const DESTINATIONS: readonly { to: string; title: string; text: string; icon: ReactNode }[] = [
  { to: "/mis-juegos", title: "Mis juegos", text: "Lo que juegas, lo pendiente y lo terminado.", icon: <LibraryIcon /> },
  { to: "/mis-deseados", title: "Mis deseados", text: "Los juegos que esperas, en tu orden.", icon: <WishlistIcon /> },
  { to: "/mis-puntuaciones", title: "Mis puntuaciones", text: "Busca, ordena y edita lo que has puntuado.", icon: <RatingsIcon /> },
  { to: "/mis-puntuaciones/estadisticas", title: "Tus estadísticas", text: "Cómo puntúas y dónde has jugado.", icon: <StatisticsIcon /> },
  { to: "/mis-plataformas", title: "Mis plataformas", text: "Elige qué plataformas salen primero.", icon: <PlatformsIcon /> },
  { to: "/mi-perfil", title: "Mi perfil", text: "Tu cuenta, tus datos y tu privacidad.", icon: <ProfileIcon /> },
];

/** The keypad's key, ice to fire: decorative, the sentence beside it names the scale. */
function ThermalKey() {
  return (
    <div aria-hidden="true" className="home-thermal-key">
      <ol className="home-thermal-steps">
        {SCALE.map((value) => (
          <li data-thermal={thermalBand(value) ?? undefined} key={value}>
            <span>{value}</span>
          </li>
        ))}
      </ol>
    </div>
  );
}

function Destinations() {
  return (
    <ul aria-label="Tu espacio en Gameómetro" className="home-personal-links">
      {DESTINATIONS.map((destination) => (
        <li key={destination.to}>
          <Link className="home-personal-link" to={destination.to}>
            <span aria-hidden="true" className="home-personal-link-icon">{destination.icon}</span>
            <span className="home-personal-link-title">{destination.title}</span>{" "}
            <span className="home-personal-link-text">{destination.text}</span>
            <span aria-hidden="true" className="home-chevron" />
          </Link>
        </li>
      ))}
    </ul>
  );
}

/**
 * The player's corner (#382), scoped to the current session. A signed-in player gets the way into
 * their own pages; an anonymous visitor gets what an account adds and the BFF's own sign-in and
 * registration, returning here. The home never reads personal data: those pages read it themselves.
 * While the session is unknown the section waits quietly, and an unavailable session read never
 * claims the visitor is signed out.
 */
export function HomePersonal() {
  const session = useSession();
  const location = useLocation();
  const returnTo = location.pathname + location.search;
  const authenticated = session.data?.authenticated === true;
  const anonymous = session.data?.authenticated === false;

  return (
    <section
      aria-busy={session.isPending}
      aria-labelledby="home-personal-title"
      className="home-personal"
      data-session={authenticated ? "authenticated" : anonymous ? "anonymous" : "unknown"}
    >
      <div className="home-personal-intro">
        <h2 className="home-personal-title" id="home-personal-title">
          Tu criterio. <span>Tu historia.</span>
        </h2>
        {session.isPending ? (
          <span aria-hidden="true" className="skeleton home-personal-skeleton" />
        ) : anonymous ? (
          <>
            <p className="home-personal-lead">
              Con tu cuenta, cada juego que puntúas se queda contigo: Gameómetro guarda tu colección
              y te enseña cómo juegas.
            </p>
            <ThermalKey />
            <nav aria-label="Tu cuenta en Gameómetro" className="home-personal-actions">
              <a className="button button-primary" href={authenticationStartUrl(returnTo, true)}>
                Crear cuenta
              </a>
              <a className="button" href={authenticationStartUrl(returnTo)}>
                Iniciar sesión
              </a>
            </nav>
          </>
        ) : (
          <p className="home-personal-lead">
            {authenticated
              ? "Tus juegos, tus deseados, tus puntuaciones y tus plataformas, a un paso."
              : "Tus juegos, tus deseados, tus puntuaciones y tus plataformas siguen en su sitio."}
          </p>
        )}
      </div>
      {session.isPending ? null : anonymous ? (
        <ul aria-label="Lo que añade tu cuenta" className="home-personal-features">
          {FEATURES.map((feature) => (
            <li className="home-personal-feature" key={feature.title}>
              <h3>{feature.title}</h3>
              <p>{feature.text}</p>
            </li>
          ))}
        </ul>
      ) : (
        <Destinations />
      )}
    </section>
  );
}
