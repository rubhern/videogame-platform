import { Link } from "react-router-dom";

import { GameGenres } from "../../shared/catalogue/game-genres";
import { PlatformMarks } from "../../shared/catalogue/platform-marks";
import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import { useGameLinkState } from "../../shared/navigation/use-game-link-state";
import { DiscoveryScores } from "../../shared/score/discovery-scores";
import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import { ChevronIcon } from "./featured-icons";
import { ReleasesPagination } from "./releases-pagination";
import { countLabel, dayHeading } from "./release-calendar-model";
import type { ReleaseListItem } from "./releases-view-model";

/** The day's leading games as the explorer's one-day read returns them. */
export type CalendarDayPreview =
  | { status: "loading" }
  | { status: "ready"; items: ReleaseListItem[]; page: { number: number; totalPages: number; totalItems: number } }
  | { status: "error"; correlationId: string | null };

type ReleaseCalendarDayProps = {
  date: string;
  /** The calendar's own count for the day, which the explorer's total for that day equals. */
  gameCount: number;
  /** How many leading games the day previews, from the served month. */
  previewSize: number;
  preview: CalendarDayPreview;
  explorerPath: string;
  pagePath: (page: number) => string;
  onRetry: () => void;
};

/** A card draws this many platform marks; every further platform stays named and counted. */
const VISIBLE_PLATFORMS = 3;

function CalendarReleaseRow({ item }: { item: ReleaseListItem }) {
  const gamePath = `/games/${item.gameId}/${item.slug}`;
  const linkState = useGameLinkState();
  const group = item.releaseGroups[0];
  return (
    <article className="calendar-release">
      <CatalogueCover caption={false} cover={item.cover} linkState={linkState} to={gamePath} />
      <div className="calendar-release-copy">
        <h3 className="calendar-release-title">
          <Link state={linkState} to={gamePath}>
            {item.title}
          </Link>
        </h3>
        {group === undefined ? null : (
          <div className="calendar-release-meta">
            <PlatformMarks
              label={`Plataformas de ${item.title}`}
              marks={group.platforms.map((name, index) => {
                const id = group.platformIds[index] ?? "";
                return { key: `${id}-${index}`, name, icon: platformIcon(id, name) };
              })}
              visible={VISIBLE_PLATFORMS}
            />
            <span className="calendar-release-region">{group.region}</span>
          </div>
        )}
        <GameGenres genres={item.genres} />
        {group?.review ? (
          <p className="badge badge-warning calendar-release-review">
            Información pendiente de revisión
          </p>
        ) : null}
      </div>
      {item.scores.length === 0 ? null : (
        <div className="calendar-release-scores">
          <DiscoveryScores scores={item.scores} title={item.title} />
        </div>
      )}
      <span aria-hidden="true" className="calendar-release-go">
        <ChevronIcon direction="next" />
      </span>
    </article>
  );
}

/**
 * The chosen day under the calendar (#250): its heading under the section's short fire rule, how
 * many games it holds, their bounded pages in interest order and **Ver todos los lanzamientos del
 * día**, which opens the explorer on that day in the same order.
 */
export function ReleaseCalendarDay({
  date,
  gameCount,
  previewSize,
  preview,
  explorerPath,
  pagePath,
  onRetry,
}: ReleaseCalendarDayProps) {
  const total = preview.status === "ready" ? preview.page.totalItems : gameCount;
  const summary = total === 0 ? "Sin lanzamientos" : countLabel(total);

  return (
    <section aria-labelledby="calendar-day-title" className="calendar-day-panel">
      <header className="calendar-day-panel-head">
        <h2 className="calendar-day-panel-title" id="calendar-day-title">
          <time dateTime={date}>{dayHeading(date)}</time>
        </h2>
        <p className="calendar-day-panel-count" role="status">
          {summary}
        </p>
        {gameCount === 0 ? null : (
          <Link className="button calendar-day-all" to={explorerPath}>
            Ver todos los lanzamientos del día
            <ChevronIcon direction="next" />
          </Link>
        )}
      </header>

      {gameCount === 0 ? (
        <p className="calendar-day-empty">
          Este día no tiene lanzamientos con fecha exacta para estos filtros.
        </p>
      ) : null}

      {gameCount > 0 && preview.status === "loading" ? (
        <ul aria-hidden="true" className="calendar-release-list">
          {Array.from({ length: Math.min(gameCount, previewSize) }, (_, index) => (
            <li className="calendar-release calendar-release-pending" key={index}>
              <span className="skeleton calendar-release-pending-cover" />
              <span className="calendar-release-pending-lines">
                <span className="skeleton skeleton-title" />
                <span className="skeleton skeleton-meta" />
              </span>
            </li>
          ))}
        </ul>
      ) : null}

      {gameCount > 0 && preview.status === "error" ? (
        <div className="notice notice-danger calendar-day-error" role="alert">
          <p className="notice-kicker">Error de carga</p>
          <h3>No se pudieron cargar los lanzamientos del día</h3>
          <button className="button button-danger" onClick={onRetry} type="button">
            Reintentar
          </button>
          {preview.correlationId === null ? null : (
            <p className="notice-reference">Referencia para soporte: {preview.correlationId}</p>
          )}
        </div>
      ) : null}

      {preview.status === "ready" && preview.items.length > 0 ? (
        <ol aria-label={`Lanzamientos del ${dayHeading(date).toLowerCase()}`} className="calendar-release-list">
          {preview.items.map((item) => (
            <li key={item.gameId}>
              <CalendarReleaseRow item={item} />
            </li>
          ))}
        </ol>
      ) : null}
      {preview.status === "ready" && <ReleasesPagination page={preview.page} pathFor={pagePath} preventScrollReset />}
    </section>
  );
}
