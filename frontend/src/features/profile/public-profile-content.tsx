import { skipToken, useQuery } from "@tanstack/react-query";
import { useId, useRef, useState, type KeyboardEvent } from "react";
import { Link } from "react-router-dom";
import { AppSelect, type AppSelectOption } from "../../shared/ui/app-select";
import { GameMeter } from "../../shared/score/game-meter";
import { thermalBand, thermalLabels } from "../../shared/score/thermal-band";
import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import { HeroTitle } from "../../shared/ui/hero-title";
import { StageLight } from "../../shared/ui/stage-light";
import { formatPersonalScore } from "../ratings/personal-score";
import { toCover } from "../search/game-search-view-model";
import { ProfileError } from "./profile-api";
import {
  getPublicProfileSection,
  PUBLIC_PROFILES_KEY,
  type PublicProfileRatingSort,
  type PublicProfileSection,
} from "./public-profile-api";
import { PublicProfileStatisticsPanel } from "./public-profile-statistics";

type ProfileTab = PublicProfileSection | "statistics";

const sectionLabels: Record<ProfileTab, string> = {
  ratings: "Puntuaciones",
  wishlist: "Deseados",
  statistics: "Estadísticas",
};

const ratingOrders = [
  { value: "ratingDate", label: "Notas recientes", icon: "clock" },
  { value: "scoreDesc", label: "Nota más alta", icon: "descending" },
  { value: "score", label: "Nota más baja", icon: "ascending" },
  { value: "releaseDate", label: "Lanzamientos recientes", icon: "descending" },
  { value: "interest", label: "Mayor interés", icon: "rating" },
] as const satisfies readonly (AppSelectOption & { value: PublicProfileRatingSort })[];

type ProfileGame = Awaited<ReturnType<typeof getPublicProfileSection>>["items"][number];

function visibleCount(section: PublicProfileSection, count: number) {
  if (section === "ratings") return count === 1 ? "1 puntuación" : `${count} puntuaciones`;
  return count === 1 ? "1 juego deseado" : `${count} juegos deseados`;
}

export function PublicProfileContent({
  alias,
  sections,
  initialSection,
}: {
  alias: string;
  sections: readonly PublicProfileSection[];
  initialSection: PublicProfileSection;
}) {
  const id = useId();
  const tabs: readonly ProfileTab[] = [...sections, "statistics"];
  const [selected, setSelected] = useState<ProfileTab>(initialSection);
  const [focusedTab, setFocusedTab] = useState<ProfileTab | null>(null);
  const [pages, setPages] = useState({ ratings: 1, wishlist: 1 });
  const [order, setOrder] = useState<PublicProfileRatingSort>("ratingDate");
  const tablist = useRef<HTMLDivElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const section = tabs.includes(selected) ? selected : initialSection;
  const tabStop = focusedTab && tabs.includes(focusedTab) ? focusedTab : section;
  const listSection = section === "statistics" ? null : section;
  const page = listSection === null ? 1 : pages[listSection];
  const sort = listSection === "ratings" ? order : undefined;
  const label = sectionLabels[section];
  const { data, error, isPending, refetch } = useQuery({
    queryKey: [...PUBLIC_PROFILES_KEY, alias, "games", listSection, page, sort],
    queryFn: listSection === null ? skipToken
      : ({ signal }) => getPublicProfileSection(alias, listSection, page, signal, sort),
    gcTime: 0,
    staleTime: 0,
    retry: false,
  });
  const lights = error ? [] : (data?.items ?? [])
    .filter(game => "attribution" in game.primaryCover && game.primaryCover.attribution)
    .slice(0, 3)
    .map(game => game.primaryCover.url);

  function moveTab(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    const last = tabs.length - 1;
    const next = event.key === "ArrowLeft" ? (index === 0 ? last : index - 1)
      : event.key === "ArrowRight" ? (index === last ? 0 : index + 1)
        : event.key === "Home" ? 0 : event.key === "End" ? last : null;
    if (next === null) return;
    event.preventDefault();
    tablist.current?.querySelectorAll<HTMLButtonElement>('[role="tab"]')[next]?.focus();
  }

  function goToPage(number: number) {
    if (listSection === null) return;
    setPages({ ...pages, [listSection]: number });
    heading.current?.focus();
  }

  const unavailable = error instanceof ProfileError && error.status === 404;

  return (
    <section className="hero-page profile-page public-player-page" aria-labelledby="public-profile-title">
      <div aria-hidden="true" className="profile-stage" />
      <StageLight images={lights} />
      <div className="page-container profile-content">
        <header className="page-head public-profile-head">
          <div className="public-profile-identity">
            <HeroTitle id="public-profile-title" title={`@${alias}`} />
            <Link className="text-link public-profile-report" to={`/contacto?perfil=${encodeURIComponent(alias)}`}>
              Denunciar perfil
            </Link>
          </div>
        </header>
        <div className="public-profile-tabs" role="tablist" aria-label="Contenido compartido" ref={tablist}
          onBlur={event => {
            if (!event.currentTarget.contains(event.relatedTarget)) setFocusedTab(null);
          }}>
          {tabs.map((entry, index) => (
            <button
              aria-controls={`${id}-${entry}-panel`}
              aria-selected={section === entry}
              id={`${id}-${entry}-tab`}
              key={entry}
              onClick={() => setSelected(entry)}
              onFocus={() => setFocusedTab(entry)}
              onKeyDown={event => moveTab(event, index)}
              role="tab"
              tabIndex={entry === tabStop ? 0 : -1}
              type="button"
            >
              {sectionLabels[entry]}
            </button>
          ))}
        </div>
        {tabs.map(entry => (
          <div
            aria-labelledby={`${id}-${entry}-tab`}
            className="public-profile-section"
            hidden={section !== entry}
            id={`${id}-${entry}-panel`}
            key={entry}
            role="tabpanel"
            tabIndex={0}
          >
            {section !== entry ? null : section === "statistics" ? (
              <PublicProfileStatisticsPanel alias={alias} />
            ) : (
              <>
                <h2 className="sr-only" ref={heading} tabIndex={-1}>{label}</h2>
                {section === "ratings" ? (
                  <div className="public-profile-sort">
                    <AppSelect label="Ordenar puntuaciones" value={order} options={ratingOrders}
                      icon="descending" onChange={value => {
                        const choice = ratingOrders.find(option => option.value === value);
                        if (choice) {
                          setOrder(choice.value);
                          setPages({ ...pages, ratings: 1 });
                        }
                      }} />
                    {order === "interest" ? <p>Según las visitas registradas por IGDB en el catálogo.</p> : null}
                  </div>
                ) : null}
                {isPending ? (
                  <>
                    <p className="result-count" role="status">Cargando {label.toLowerCase()}…</p>
                    {section === "ratings" ? <PublicRatingsLoading /> : null}
                  </>
                ) : unavailable ? (
                  <p className="notice notice-info" role="status">Esta sección ya no está disponible.</p>
                ) : error ? (
                  <div className="notice notice-danger" role="alert">
                    <p>No se pudo cargar esta sección.</p>
                    <button type="button" className="button" onClick={() => void refetch()}>Reintentar</button>
                  </div>
                ) : data ? (
                  <>
                    <p className="result-count public-profile-count" role="status">
                      {visibleCount(section, data.page.totalItems)}
                    </p>
                    {data.items.length === 0 ? (
                      <p className="notice notice-empty">
                        {section === "ratings" ? "Aún no hay puntuaciones." : "Aún no hay juegos deseados."}
                      </p>
                    ) : (
                      <ul className={section === "ratings" ? "public-profile-ratings my-ratings-list" : "public-profile-wishlist"}>
                        {data.items.map(game => <PublicGame key={game.gameId} game={game} section={section} />)}
                      </ul>
                    )}
                    {page > 1 || data.page.totalPages > 1 ? (
                      <nav aria-label={`Páginas de ${label.toLowerCase()}`} className="pagination public-profile-pagination">
                        <button type="button" className="button view-action view-action-back" disabled={page === 1}
                          onClick={() => goToPage(page - 1)}><span aria-hidden="true">←</span> Anterior</button>
                        <span className="result-count" role="status">Página {page} de {Math.max(data.page.totalPages, page)}</span>
                        <button type="button" className="button view-action" disabled={page >= data.page.totalPages}
                          onClick={() => goToPage(page + 1)}>Siguiente <span aria-hidden="true">→</span></button>
                      </nav>
                    ) : null}
                  </>
                ) : null}
              </>
            )}
          </div>
        ))}
      </div>
    </section>
  );
}

function PublicRatingsLoading() {
  return (
    <div className="public-profile-ratings my-ratings-list" aria-hidden="true">
      {[0, 1, 2].map(row => (
        <div className="my-rating-card my-rating-card-loading public-profile-rating" key={row}>
          <span className="skeleton my-rating-skeleton-cover" />
          <div className="my-rating-body"><span className="skeleton skeleton-title" /></div>
          <span className="skeleton my-rating-skeleton-score" />
        </div>
      ))}
    </div>
  );
}

function PublicGame({ game, section }: { game: ProfileGame; section: PublicProfileSection }) {
  const path = game.slug && game.canonicalTitle ? `/games/${game.gameId}/${game.slug}` : undefined;
  const cover = toCover(game.primaryCover);
  const score = section === "ratings" ? game.score : undefined;
  const band = score === undefined ? null : thermalBand(score);
  return (
    <li
      className={section === "ratings" ? "my-rating-card public-profile-rating" : "public-profile-wish"}
      data-thermal={band ?? undefined}
    >
      <CatalogueCover cover={cover} {...(path ? { to: path } : {})} caption={cover.kind !== "fallback"} />
      <div className={section === "ratings" ? "my-rating-body" : undefined}>
        <h3 className="card-title">
          {path ? <Link to={path}>{game.canonicalTitle}</Link> : "Juego no disponible"}
        </h3>
      </div>
      {score !== undefined ? (
        <div className="public-profile-reading">
          <GameMeter className="public-profile-meter" value={score} />
          <p>
            <strong>{formatPersonalScore(score)}<small aria-hidden="true">/10</small><span className="sr-only"> de 10</span></strong>
            {band ? <span className="public-profile-temperature">{thermalLabels[band]}</span> : null}
          </p>
        </div>
      ) : null}
    </li>
  );
}
