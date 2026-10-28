import { useState } from "react";
import { Link } from "react-router-dom";

import { NeedleIcon } from "../../shared/brand/needle-icon";
import { PlatformMarks, type PlatformMark } from "../../shared/catalogue/platform-marks";
import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import { useGameLinkState } from "../../shared/navigation/use-game-link-state";
import { resolvePlatformFilter } from "../platform-preferences/interests";
import { useInterestsSwitch } from "../platform-preferences/interests-switch";
import { useInterests } from "../platform-preferences/use-platform-preferences";
import { FeaturedArtFrame } from "../releases/featured-art";
import { FeaturedHero } from "../releases/featured-hero";
import { DialSilhouette } from "../releases/month-dial";
import { DIAL_HEIGHT, DIAL_WIDTH } from "../releases/month-dial-geometry";
import type { FeaturedItem } from "../releases/featured-releases-view-model";
import { FEATURED_PATH } from "../releases/featured-search";
import type { ReleaseContextGroup } from "../releases/releases-view-model";
import { releasesRangePath } from "../releases/releases-search";
import { useFeaturedReleasesQuery } from "../releases/use-featured-releases-query";
import { HOME_FEATURED_SEARCH } from "./home-path";
import { HomeNotice } from "./home-section";

/** The rail shows this many of the week's further featured releases; fewer games, fewer cards. */
const MORE_HIGHLIGHTS = 4;

function platformMarks(group: ReleaseContextGroup): PlatformMark[] {
  return group.platforms.map((name, index) => {
    const id = group.platformIds[index] ?? "";
    return { key: id === "" ? `${name}-${index}` : id, name, icon: platformIcon(id, name) };
  });
}

/** The game's page first, as the primary ice action, then Destacados on the same week. */
function HomeFeatureActions({ gameId, slug, title }: { gameId: string; slug: string; title: string }) {
  const linkState = useGameLinkState();
  return (
    <>
      <Link
        aria-label={`Ver ficha de ${title}`}
        className="button button-primary home-feature-primary"
        state={linkState}
        to={`/games/${gameId}/${slug}`}
      >
        Ver ficha
        <span aria-hidden="true" className="home-chevron" />
      </Link>
      <Link className="button home-feature-secondary" to={FEATURED_PATH}>
        Ver destacados
      </Link>
    </>
  );
}

/** One more featured release of the week: its landscape art, its day, its title and its platforms. */
function HomeHighlightCard({ item }: { item: FeaturedItem }) {
  const linkState = useGameLinkState();
  const gamePath = `/games/${item.gameId}/${item.slug}`;
  const [group] = item.releaseGroups;
  const [logoFailed, setLogoFailed] = useState(false);
  const first = item.art[0];
  // A screenshot never carries a title of its own, so the game's logo stands on it, as on Destacados.
  const logo =
    !logoFailed && first?.kind === "screenshot" && first.presentation === "fill" ? item.logo : null;
  return (
    <article className="home-more-card">
      <Link aria-hidden="true" className="home-more-media" state={linkState} tabIndex={-1} to={gamePath}>
        <FeaturedArtFrame art={item.art} scale="card" />
        {logo === null ? null : (
          <img
            alt=""
            className="home-more-logo"
            decoding="async"
            loading="lazy"
            onError={() => setLogoFailed(true)}
            src={logo.url}
          />
        )}
      </Link>
      <div className="home-more-body">
        {group === undefined ? null : (
          <p className="home-more-date">
            <NeedleIcon className="home-more-needle" />
            <span aria-hidden="true">{group.shortDate}</span>
            <span className="sr-only">{group.date}</span>
          </p>
        )}
        <h4 className="home-more-title">
          <Link state={linkState} title={item.title} to={gamePath}>
            {item.title}
          </Link>
        </h4>
        {group === undefined ? null : (
          <PlatformMarks label={`Plataformas de ${item.title}`} marks={platformMarks(group)} visible={3} />
        )}
      </div>
    </article>
  );
}

export function HomeWeekLoading() {
  return (
    <div aria-hidden="true" className="home-week-loading featured-loading">
      <div className="featured-hero featured-hero-placeholder">
        <div className="featured-hero-visual">
          <span className="skeleton featured-skeleton-art" />
        </div>
        <div className="featured-dial">
          <DialSilhouette className="featured-dial-g" height={DIAL_HEIGHT} width={DIAL_WIDTH} />
          <div className="featured-hero-body">
            <span className="skeleton featured-skeleton-badge" />
            <span className="skeleton featured-skeleton-title" />
            <span className="skeleton featured-skeleton-title featured-skeleton-title-short" />
            <span className="skeleton skeleton-meta" />
          </div>
        </div>
      </div>
      <ul className="home-more-list">
        {Array.from({ length: MORE_HIGHLIGHTS }, (_, index) => (
          <li className="home-more-card" key={index}>
            <span className="skeleton home-more-media" />
            <span className="skeleton skeleton-title" />
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * Destacados de la semana as the home's stage (#382): the current week's featured release under the
 * visitor's preferred platforms (#295), exactly as Destacados reads it, and up to four more of its
 * featured releases. An unranked or empty week, a catalogue still preparing and a failed read each
 * replace only this stage with their own notice; Explorar lanzamientos stays one step away.
 */
export function HomeWeekHighlights() {
  const interests = useInterests();
  const interestsSwitch = useInterestsSwitch();
  const platformFilter = resolvePlatformFilter([], interestsSwitch.enabled, interests);
  const query = useFeaturedReleasesQuery(
    HOME_FEATURED_SEARCH,
    platformFilter.platformIds,
    interests.status !== "pending",
  );
  const interestsOn = platformFilter.mode === "on";

  let content;
  if (query.isPending || query.isPlaceholderData) {
    content = <HomeWeekLoading />;
  } else if (query.isError) {
    content =
      query.error.code === "CATALOGUE_NOT_READY" ? (
        <HomeNotice
          actions={
            <button className="button" onClick={() => void query.refetch()} type="button">
              Reintentar
            </button>
          }
          kicker="Datos en preparación"
          title="Los juegos todavía no están disponibles"
          tone="info"
        >
          <p>Aún no tenemos datos válidos de juegos, pero tranquilo que llegarán.</p>
        </HomeNotice>
      ) : (
        <HomeNotice
          actions={
            <button className="button button-danger" onClick={() => void query.refetch()} type="button">
              Reintentar
            </button>
          }
          correlationId={query.error.correlationId}
          kicker="Error de carga"
          title="Destacados se ha tomado un respiro"
          tone="danger"
        >
          <p>A Gameómetro le está costando un poco. Prueba más tarde; el resto de Gameómetro sigue aquí.</p>
        </HomeNotice>
      );
  } else {
    const model = query.data;
    const explore = releasesRangePath(model.window.from, model.window.to);
    if (model.lead === null) {
      const unranked = model.selection.status === "popularity-unavailable";
      content = (
        <HomeNotice
          actions={
            <>
              {interestsOn ? (
                <button className="button button-primary" onClick={() => interestsSwitch.set(false)} type="button">
                  Ver todas las plataformas
                </button>
              ) : null}
              <Link className={`button${interestsOn ? "" : " button-primary"}`} to={explore}>
                Explorar lanzamientos
              </Link>
              <Link className="button" to={FEATURED_PATH}>
                Ver Destacados
              </Link>
            </>
          }
          kicker={unranked ? "Sin datos de Hype" : "Sin lanzamientos"}
          title={
            unranked
              ? `Aún no hay Destacados de ${model.periodPhrase}`
              : interestsOn
                ? `Tus plataformas no tienen lanzamientos en ${model.periodPhrase}`
                : `No hay lanzamientos en ${model.periodPhrase}`
          }
          tone={unranked ? "info" : "empty"}
        >
          <p>
            {unranked
              ? `De momento, Gameómetro no tiene Hype para lanzamientos de esta semana${interestsOn ? " en tus plataformas" : ""}.`
              : interestsOn
                ? "Gameómetro no tiene lanzamientos para tus plataformas esta semana. Prueba con todas las plataformas."
                : "Gameómetro no tiene lanzamientos con día confirmado dentro de esta semana."}
          </p>
        </HomeNotice>
      );
    } else {
      const more = model.others.slice(0, MORE_HIGHLIGHTS);
      content = (
        <>
          {/* Destacados's own hero: the week's temporal G frames the release on its art, and the
              home's two ways out close the copy inside it. */}
          <div className="home-feature">
            <FeaturedHero
              actions={<HomeFeatureActions gameId={model.lead.gameId} slug={model.lead.slug} title={model.lead.title} />}
              key={model.lead.gameId}
              granularity={model.granularity}
              lead={model.lead}
              periodPhrase={model.periodPhrase}
              timeline={model.timeline}
            />
          </div>
          {more.length === 0 ? null : (
            <section aria-labelledby="home-more-title" className="home-more">
              <div className="home-more-head">
                <h3 className="home-more-heading" id="home-more-title">
                  Más destacados de la semana
                </h3>
                {/* The explorer opens on exactly the represented week (#285). */}
                <Link className="home-more-action view-action" to={explore}>
                  Ver todos los lanzamientos de la semana <span aria-hidden="true">→</span>
                </Link>
              </div>
              <ul className="home-more-list">
                {more.map((item) => (
                  <li className="min-w-0" key={item.gameId}>
                    <HomeHighlightCard item={item} />
                  </li>
                ))}
              </ul>
            </section>
          )}
        </>
      );
    }
  }

  return (
    <section aria-labelledby="home-week-title" className="home-week">
      <h2 className="sr-only" id="home-week-title">
        Destacados de la semana
      </h2>
      {content}
    </section>
  );
}
