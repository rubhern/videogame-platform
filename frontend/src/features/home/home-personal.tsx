import { useLocation } from "react-router-dom";

import { thermalBand } from "../../shared/score/thermal-band";
import { authenticationStartUrl } from "../session/auth-entry";
import { useSession } from "../session/use-session";
import { wishlistSessionScope } from "../wishlist/wishlist-api";
import { HomePersonalActivity, PersonalDestinations } from "./home-personal-activity";

/** The rating scale the keypad uses, from ice to fire; each value takes its own temperature. */
const SCALE = Array.from({ length: 10 }, (_, index) => index + 1);

/** What a player account adds today; every entry names an implemented feature. */
const FEATURES = [
  {
    title: "Puntúa en un toque",
    text: "Del 1 al 10 desde la ficha de cada juego o directamente en el TOPómetro, y por plataforma si quieres.",
  },
  {
    title: "Sigue lo que esperas",
    text: "Tus deseados en tu orden, con su fecha, y Explorar o el calendario solo con ellos.",
  },
  {
    title: "Lleva tu colección",
    text: "Pendiente, Jugando, Completado o Abandonado, y estadísticas de cómo puntúas.",
  },
  {
    title: "Comparte tu perfil",
    text: "Un enlace público con tus puntuaciones y tus deseados, solo si tú quieres.",
  },
] as const;

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

const heading = (
  <h2 className="home-personal-title" id="home-personal-title">
    Tu criterio. <span>Tu historia.</span>
  </h2>
);

/**
 * The player's corner (#382), scoped to the current session. A signed-in player gets their own
 * activity, read from the first page of their private collections; an anonymous visitor gets what
 * an account adds and the BFF's own sign-in and registration, returning here, and nothing personal
 * is read. While the session is unknown the section waits quietly, and an unavailable session read
 * offers the personal pages without claiming the visitor is signed out.
 */
export function HomePersonal() {
  const session = useSession();
  const location = useLocation();
  const returnTo = location.pathname + location.search;
  const authenticated = session.data?.authenticated === true && !session.isError;
  const anonymous = session.data?.authenticated === false && !session.isError;
  const scope = wishlistSessionScope(session.data);

  return (
    <section
      aria-busy={session.isPending}
      aria-labelledby="home-personal-title"
      className="home-personal"
      data-session={authenticated ? "authenticated" : anonymous ? "anonymous" : "unknown"}
    >
      {session.isPending ? (
        <div className="home-personal-intro">
          {heading}
          <span aria-hidden="true" className="skeleton home-personal-skeleton" />
        </div>
      ) : authenticated && scope !== undefined ? (
        <HomePersonalActivity heading={heading} scope={scope} />
      ) : anonymous ? (
        <>
          <div className="home-personal-intro">
            {heading}
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
          </div>
          <ul aria-label="Lo que añade tu cuenta" className="home-personal-features">
            {FEATURES.map((feature) => (
              <li className="home-personal-feature" key={feature.title}>
                <h3>{feature.title}</h3>
                <p>{feature.text}</p>
              </li>
            ))}
          </ul>
        </>
      ) : (
        <>
          <div className="home-personal-intro">
            {heading}
            <p className="home-personal-lead">
              Tus juegos, tus deseados y tus puntuaciones siguen en su sitio.
            </p>
          </div>
          <PersonalDestinations />
        </>
      )}
    </section>
  );
}
