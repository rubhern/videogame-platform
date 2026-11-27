import { useQuery } from "@tanstack/react-query";
import { useId, type ReactNode } from "react";
import { Link } from "react-router-dom";

import { formatReleaseDate, formatReleaseDateShort } from "../../shared/catalogue/release-date";
import { useGameLinkState } from "../../shared/navigation/use-game-link-state";
import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import { LIBRARY_KEY, libraryStateSlugs, listLibrary } from "../library/library-api";
import { listMyRatings, MY_RATINGS_KEY } from "../ratings/my-ratings-api";
import { toCover } from "../releases/releases-view-model";
import { LibraryIcon, RatingsIcon, StatisticsIcon, WishlistIcon } from "../session/account-icons";
import { listWishlist, WISHLIST_KEY } from "../wishlist/wishlist-api";
import { HomeNotice } from "./home-section";

/** Each shelf shows the first games of its own page, never more. */
const SHELF_SIZE = 3;

const PLAYING_PATH = `/mis-juegos?estado=${libraryStateSlugs.playing}`;
const COMPLETED_PATH = `/mis-juegos?estado=${libraryStateSlugs.completed}`;

const countFormat = new Intl.NumberFormat("es-ES");
const sinceFormat = new Intl.DateTimeFormat("es-ES", {
  day: "numeric", month: "long", year: "numeric", timeZone: "Europe/Madrid",
});

type WishlistTiming = Pick<Awaited<ReturnType<typeof listWishlist>>["items"][number], "timing" | "releaseDate">;

/**
 * The player's own activity for the home: the first page of three private reads that already
 * exist, each bounded by its page size and counted in PostgreSQL. Ratings count the scored
 * games, the library counts every state and lists what is being played, most recently changed
 * first, and the wishlist lists the first games in the player's own order. The keys sit under
 * each feature's own prefix, so a rating, a state or a wish changed anywhere refreshes them, and
 * nothing personal outlives the home.
 */
function usePersonalActivity(scope: number) {
  const library = useQuery({
    queryKey: [...LIBRARY_KEY, "home", scope],
    queryFn: ({ signal }) => listLibrary({ state: "playing", page: 1, pageSize: SHELF_SIZE }, signal),
    gcTime: 0,
  });
  const wishlist = useQuery({
    queryKey: [...WISHLIST_KEY, "home", scope],
    queryFn: ({ signal }) => listWishlist({ page: 1, pageSize: SHELF_SIZE }, signal),
    gcTime: 0,
  });
  const ratings = useQuery({
    queryKey: [...MY_RATINGS_KEY, "home", scope],
    queryFn: ({ signal }) => listMyRatings({ page: 1, pageSize: 1 }, signal),
    gcTime: 0,
  });
  return { library, wishlist, ratings };
}

/** The player's own pages, as a fallback when their activity cannot be read. */
const DESTINATIONS: readonly { to: string; title: string; text: string; icon: ReactNode }[] = [
  { to: "/mis-juegos", title: "Mis juegos", text: "Lo que juegas, lo pendiente y lo terminado.", icon: <LibraryIcon /> },
  { to: "/mis-deseados", title: "Mis deseados", text: "Los juegos que esperas, en tu orden.", icon: <WishlistIcon /> },
  { to: "/mis-puntuaciones", title: "Mis puntuaciones", text: "Busca, ordena y edita lo que has puntuado.", icon: <RatingsIcon /> },
  { to: "/mis-puntuaciones/estadisticas", title: "Tus estadísticas", text: "Cómo puntúas y dónde has jugado.", icon: <StatisticsIcon /> },
];

type ActivityProps = { heading: ReactNode; scope: number };

/**
 * A signed-in player's corner: how much they have rated, played, finished and wished for, then
 * what they are playing now and what they await most. Loading, failure and an empty shelf are
 * distinct; a failed read keeps the way into every personal page.
 */
export function HomePersonalActivity({ heading, scope }: ActivityProps) {
  const { library, wishlist, ratings } = usePersonalActivity(scope);
  const reads = [library, wishlist, ratings];
  const failed = reads.filter((read) => read.isError && read.data === undefined);

  if (failed.length > 0) {
    return (
      <>
        <div className="home-personal-intro">
          {heading}
          <HomeNotice
            actions={
              <button className="button" onClick={() => failed.forEach((read) => void read.refetch())} type="button">
                Reintentar
              </button>
            }
            kicker="Tu actividad"
            title="No se pudo cargar tu actividad"
            tone="danger"
          >
            <p>Tus juegos, tus deseados y tus puntuaciones siguen en su sitio.</p>
          </HomeNotice>
        </div>
        <PersonalDestinations />
      </>
    );
  }

  if (library.data === undefined || wishlist.data === undefined || ratings.data === undefined) {
    return (
      <>
        <div className="home-personal-intro">
          {heading}
          <p className="sr-only" role="status">Cargando tu actividad…</p>
          <span aria-hidden="true" className="skeleton home-personal-tally-skeleton" />
        </div>
        <div aria-hidden="true" className="home-personal-shelves">
          <span className="skeleton home-personal-shelf-skeleton" />
          <span className="skeleton home-personal-shelf-skeleton" />
        </div>
      </>
    );
  }

  const counts = library.data.counts;
  const tally = [
    { to: "/mis-puntuaciones", count: ratings.data.page.totalItems, one: "Puntuado", other: "Puntuados" },
    { to: PLAYING_PATH, count: counts.playing, one: "Jugando", other: "Jugando" },
    { to: COMPLETED_PATH, count: counts.completed, one: "Completado", other: "Completados" },
    { to: "/mis-deseados", count: counts.wished, one: "Deseado", other: "Deseados" },
  ];

  return (
    <>
      <div className="home-personal-intro">
        {heading}
        <ul aria-label="Tu actividad en cifras" className="home-personal-tally">
          {tally.map((entry) => (
            <li key={entry.to}>
              <Link to={entry.to}>
                <strong>{countFormat.format(entry.count)}</strong>{" "}
                <span>{entry.count === 1 ? entry.one : entry.other}</span>
              </Link>
            </li>
          ))}
        </ul>
        <Link className="home-personal-more view-action" to="/mis-puntuaciones/estadisticas">
          Ver tus estadísticas <span aria-hidden="true">→</span>
        </Link>
      </div>
      <div className="home-personal-shelves">
        <Shelf
          empty="Ningún juego en Jugando. Márcalo desde su ficha y lo verás aquí."
          link={{ label: "Mis juegos", to: PLAYING_PATH }}
          title="Jugando ahora"
        >
          {library.data.items.length === 0 ? null : (
            <ul className="home-personal-rows" role="list">
              {library.data.items.map((game) => (
                <ShelfRow
                  cover={toCover(game.primaryCover)}
                  gameId={game.gameId}
                  key={game.gameId}
                  meta={`Desde el ${sinceFormat.format(new Date(game.stateChangedAt))}`}
                  slug={game.slug}
                  title={game.canonicalTitle}
                />
              ))}
            </ul>
          )}
        </Shelf>
        <Shelf
          empty="Aún no has guardado juegos. Marca «La quiero» en uno y lo verás aquí."
          link={{ label: "Mis deseados", to: "/mis-deseados" }}
          title="Lo que más esperas"
        >
          {wishlist.data.items.length === 0 ? null : (
            <ol className="home-personal-rows" role="list">
              {wishlist.data.items.map((game, index) => (
                <ShelfRow
                  cover={toCover(game.primaryCover)}
                  gameId={game.gameId}
                  key={game.gameId}
                  meta={wishTiming(game)}
                  position={index + 1}
                  slug={game.slug}
                  title={game.canonicalTitle}
                />
              ))}
            </ol>
          )}
        </Shelf>
      </div>
    </>
  );
}

/** The compact date at its real precision, with the full wording for assistive technology. */
function wishTiming({ timing, releaseDate }: WishlistTiming): ReactNode {
  if (timing === "undated") return "Fecha por confirmar";
  const prefix = timing === "released" ? "Ya lanzado · " : "";
  return (
    <>
      <span aria-hidden="true">{prefix}{formatReleaseDateShort(releaseDate)}</span>
      <span className="sr-only">{prefix}{formatReleaseDate(releaseDate)}</span>
    </>
  );
}

type ShelfProps = {
  title: string;
  link: { label: string; to: string };
  empty: string;
  /** The shelf's rows, or nothing when it is empty. */
  children: ReactNode;
};

function Shelf({ title, link, empty, children }: ShelfProps) {
  const id = useId();
  return (
    <section aria-labelledby={id} className="home-personal-shelf">
      <header>
        <h3 id={id}>{title}</h3>
        <Link className="view-action" to={link.to}>
          {link.label} <span aria-hidden="true">→</span>
        </Link>
      </header>
      {children ?? <p className="home-personal-shelf-empty">{empty}</p>}
    </section>
  );
}

type ShelfRowProps = {
  gameId: string;
  slug: string | undefined;
  title: string | undefined;
  cover: ReturnType<typeof toCover>;
  meta: ReactNode;
  /** The game's place in the player's own order, when the shelf keeps one. */
  position?: number;
};

/** One game: its cover, its title as the link, and one line on where it stands for the player. */
function ShelfRow({ gameId, slug, title, cover, meta, position }: ShelfRowProps) {
  const linkState = useGameLinkState();
  // A game no longer in the current publication keeps its entry without a slug or a title.
  const to = slug === undefined ? undefined : `/games/${gameId}/${slug}`;
  const name = title ?? "Juego no disponible";
  return (
    <li className="home-personal-row" data-position={position}>
      {position === undefined ? null : (
        <span aria-hidden="true" className="home-personal-place">{position}</span>
      )}
      <CatalogueCover caption={false} cover={cover} {...(to ? { to, linkState } : {})} />
      <div className="home-personal-record">
        {to === undefined ? (
          <span>{name}</span>
        ) : (
          <Link state={linkState} to={to}>
            {position === undefined ? null : <span className="sr-only">Puesto {position}. </span>}
            {name}
          </Link>
        )}
        <p>{meta}</p>
      </div>
    </li>
  );
}

/** The four pages of the account menu's **Tu actividad**, by the names of the pages they open. */
export function PersonalDestinations() {
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
