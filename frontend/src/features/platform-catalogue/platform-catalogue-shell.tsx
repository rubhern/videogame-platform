import { useEffect, useId, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";

import { PlatformLogo } from "../../shared/catalogue/platform-logo";
import { isPlatformLogoName } from "../../shared/catalogue/platform-logo-assets";
import { AppSelect } from "../../shared/ui/app-select";
import { CatalogueLoading } from "../../shared/ui/catalogue-loading";
import type { SelectIconName } from "../../shared/ui/select-icon";
import { platformsPath, type PlatformContext } from "../platforms/platform-context";
import {
  ActiveFilterChips,
  CatalogueSearchField,
  FiltersPanel,
  ReleaseStateSwitch,
} from "./platform-catalogue-controls";
import {
  advancedFilterCount,
  effectiveSort,
  hasNarrowingFilters,
  platformCataloguePath,
  platformGamesSortLabels,
  type PlatformCatalogueSearch,
} from "./platform-catalogue-search";
import type { PlatformCatalogueViewModel } from "./platform-catalogue-view-model";
import type { PlatformCatalogueState } from "./platform-catalogue-state";
import type { PlatformGamesSort } from "./platform-games-api";
import { PlatformGameCard } from "./platform-game-card";
import { PlatformSwitcher } from "./platform-switcher";

const SORT_ICONS: Record<PlatformGamesSort, SelectIconName> = {
  release_date_desc: "descending",
  release_date_asc: "ascending",
  title_asc: "title",
  title_desc: "title",
  community_score: "gameometro",
  external_player_score: "igdb",
};

const SORT_OPTIONS = (Object.keys(platformGamesSortLabels) as PlatformGamesSort[]).map((sort) => ({
  value: sort,
  label: platformGamesSortLabels[sort],
  icon: SORT_ICONS[sort],
}));

function gamesCount(total: number): string {
  return total === 1 ? "1 juego" : `${new Intl.NumberFormat("es-ES").format(total)} juegos`;
}

function CataloguePagination({
  page,
  pathFor,
}: {
  page: PlatformCatalogueViewModel["page"];
  pathFor: (page: number) => string;
}) {
  const hasPrevious = page.number > 1 && page.number <= page.totalPages;
  const hasNext = page.number < page.totalPages;
  if (!hasPrevious && !hasNext) return null;
  return (
    <nav aria-label="Paginación de juegos" className="pagination">
      {hasPrevious ? (
        <Link className="button view-action view-action-back" to={pathFor(page.number - 1)}>
          <span aria-hidden="true">←</span> Página anterior
        </Link>
      ) : null}
      {hasNext ? (
        <Link className="button view-action" to={pathFor(page.number + 1)}>
          Página siguiente <span aria-hidden="true">→</span>
        </Link>
      ) : null}
    </nav>
  );
}

type PlatformCatalogueShellProps = {
  context: PlatformContext;
  search: PlatformCatalogueSearch;
  state: PlatformCatalogueState;
  /** The other platforms on offer for **Cambiar plataforma**; null while unknown. */
  otherPlatforms: readonly PlatformContext[] | null;
  onRetry: () => void;
};

/**
 * **Explorar catálogo** (#389): every game Gameómetro knows on one platform or platform family,
 * without typing. The platform's identity opens the page; the release state, the platform's own
 * title search and the order are always at hand, the year and genre filters fold behind
 * **Filtros** with their chips always visible, and the cover grid follows. Every choice lives in
 * the URL and returns to the first page.
 */
export function PlatformCatalogueShell({
  context,
  search,
  state,
  otherPlatforms,
  onRetry,
}: PlatformCatalogueShellProps) {
  const navigate = useNavigate();
  const filtersId = useId();
  const resultsHeadingRef = useRef<HTMLHeadingElement>(null);
  const previousPage = useRef(search.page);
  const [filtersOpen, setFiltersOpen] = useState(false);

  useEffect(() => {
    if (previousPage.current === search.page) return;
    previousPage.current = search.page;
    resultsHeadingRef.current?.focus({ preventScroll: true });
  }, [search.page]);

  const model = state.status === "ready" ? state.model : null;
  const shown = model !== null && state.status === "ready" && !state.isPlaceholderData ? model : null;
  const pathFor = (change: Partial<PlatformCatalogueSearch>) =>
    platformCataloguePath(context.key, search, { ...change, page: 1 });
  const explore = (change: Partial<PlatformCatalogueSearch>) => {
    void navigate(pathFor(change), { preventScrollReset: true });
  };
  const genreNames = new Map((model?.genres ?? []).map((genre) => [genre.id, genre.name]));
  const filterCount = advancedFilterCount(search);
  const showPlatform = context.platformIds.length > 1;
  const isBeyondLastPage =
    shown !== null && shown.page.totalPages > 0 && shown.page.number > shown.page.totalPages;
  const sort = effectiveSort(search);

  function closeFilters() {
    setFiltersOpen(false);
    resultsHeadingRef.current?.focus({ preventScroll: true });
    resultsHeadingRef.current?.scrollIntoView?.({ block: "start" });
  }

  return (
    <section aria-labelledby="platform-catalogue-title" className="platform-catalogue-page">
      <div className="page-container platform-catalogue">
        <header className="platform-catalogue-head platform-identity-box" data-logo={context.logo}>
          <nav aria-label="Ruta de navegación" className="catalogue-breadcrumbs">
            <ol>
              <li>
                <Link to="/plataformas">Plataformas</Link>
              </li>
              <li>
                <Link to={platformsPath(context)}>{context.name}</Link>
              </li>
              <li aria-current="page">Explorar catálogo</li>
            </ol>
          </nav>
          <div className="platform-catalogue-identity">
            <h1 className="page-title platform-catalogue-title" id="platform-catalogue-title">
              <span className="sr-only">Explorar catálogo de</span> {context.name}
            </h1>
            {isPlatformLogoName(context.logo) ? (
              <div aria-hidden="true" className="platform-catalogue-mark">
                <PlatformLogo name={context.logo} size="large" />
              </div>
            ) : null}
          </div>
        </header>

        <div className="catalogue-controls">
          <ReleaseStateSwitch
            pathFor={(releaseState) => pathFor({ state: releaseState })}
            state={search.state}
          />
          <CatalogueSearchField
            key={search.q ?? ""}
            onSearch={(q) => explore({ q })}
            platformName={context.name}
            query={search.q}
          />
          <div className="catalogue-controls-row">
            <AppSelect
              className="catalogue-sort"
              icon={SORT_ICONS[sort]}
              inlineLabel
              label="Ordenar por"
              onChange={(value) =>
                explore({ sort: SORT_OPTIONS.find((option) => option.value === value)?.value ?? null })
              }
              options={SORT_OPTIONS}
              value={sort}
            />
            <button
              aria-controls={filtersId}
              aria-expanded={filtersOpen}
              aria-label={
                filterCount === 0
                  ? "Filtros"
                  : `Filtros, ${filterCount} ${filterCount === 1 ? "activo" : "activos"}`
              }
              className="catalogue-filters-toggle"
              onClick={() => setFiltersOpen((open) => !open)}
              type="button"
            >
              <span aria-hidden="true" className="catalogue-filters-toggle-icon" />
              Filtros
              {filterCount > 0 ? (
                <span aria-hidden="true" className="catalogue-filters-count">
                  {filterCount}
                </span>
              ) : null}
            </button>
          </div>
        </div>

        <FiltersPanel
          genres={model?.genres ?? null}
          id={filtersId}
          onChange={explore}
          onClose={closeFilters}
          open={filtersOpen}
          search={search}
          totalItems={shown?.page.totalItems ?? null}
          years={model?.years ?? null}
        />

        <ActiveFilterChips genreNames={genreNames} pathFor={pathFor} search={search} />

        <div className="catalogue-results-head">
          <h2 className="sr-only" ref={resultsHeadingRef} tabIndex={-1}>
            Juegos de {context.name}
          </h2>
          {shown === null ? (
            <p className="catalogue-total catalogue-total-pending" aria-hidden="true" />
          ) : (
            <p className="catalogue-total" role="status">
              {state.status === "ready" && state.isRefreshing ? (
                "Actualizando juegos…"
              ) : (
                <>
                  <strong>{gamesCount(shown.page.totalItems)}</strong>
                  <span className="catalogue-total-context"> en Gameómetro</span>
                  {shown.page.totalPages > 1 && !isBeyondLastPage ? (
                    <span className="catalogue-total-page">
                      {" · "}Página {shown.page.number} de {shown.page.totalPages}
                    </span>
                  ) : null}
                </>
              )}
            </p>
          )}
          <PlatformSwitcher
            current={context}
            others={otherPlatforms}
            pathFor={(platform) =>
              platformCataloguePath(platform.key, search, {
                page: 1,
                // A genre belongs to the whole taxonomy; the next platform's facets keep it.
                genreIds: search.genreIds,
              })
            }
          />
        </div>

        {state.status === "loading" ? (
          <CatalogueLoading message="Cargando juegos…" placeholders={12} />
        ) : null}

        {state.status === "ready" && state.isPlaceholderData ? (
          <CatalogueLoading message="Cargando juegos para la nueva selección…" placeholders={12} />
        ) : null}

        {state.status === "catalogue-not-ready" ? (
          <div className="notice notice-info" role="status">
            <p className="notice-kicker">Datos en preparación</p>
            <h3>Los juegos todavía no están disponibles</h3>
            <p>Aún no tenemos datos válidos de juegos, pero tranquilo que llegarán</p>
            <button className="button" onClick={onRetry} type="button">
              Reintentar
            </button>
          </div>
        ) : null}

        {state.status === "invalid-search" ? (
          <div className="notice notice-warning" role="alert">
            <p className="notice-kicker">Revisa la búsqueda</p>
            <h3>Búsqueda no válida</h3>
            <p>Escribe alguna letra o número del título, como máximo 100 caracteres</p>
            <Link className="button button-primary" to={pathFor({ q: null })}>
              Quitar la búsqueda
            </Link>
          </div>
        ) : null}

        {state.status === "unsupported-filters" ? (
          <div className="notice notice-warning" role="alert">
            <p className="notice-kicker">Revisa la selección</p>
            <h3>Filtro no admitido</h3>
            <p>Filtros inválidos, o al menos Gameómetro no los entiende</p>
            <Link
              className="button button-primary"
              to={platformCataloguePath(context.key)}
            >
              Quitar filtros
            </Link>
          </div>
        ) : null}

        {state.status === "error" ? (
          <div className="notice notice-danger" role="alert">
            <p className="notice-kicker">Error de carga</p>
            <h3>No se pudieron cargar los juegos</h3>
            <p>A Gameómetro le está costando un poco. Prueba más tarde</p>
            <button className="button button-danger" onClick={onRetry} type="button">
              Reintentar
            </button>
            {state.correlationId === null ? null : (
              <p className="notice-reference">Referencia para soporte: {state.correlationId}</p>
            )}
          </div>
        ) : null}

        {shown === null ? null : shown.items.length === 0 ? (
          <div className="notice notice-empty" role="status">
            <p className="notice-kicker">Sin resultados</p>
            {isBeyondLastPage ? (
              <>
                <h3>Esta página ya no está disponible</h3>
                <p>Ay, esta página ya no está disponible</p>
                <Link
                  className="button button-primary"
                  to={platformCataloguePath(context.key, search, { page: shown.page.totalPages })}
                >
                  Ir a la última página
                </Link>
              </>
            ) : hasNarrowingFilters(search) ? (
              <>
                <h3>Ningún juego coincide con estos filtros</h3>
                <p>Afinando la búsqueda seguro que encontramos alguno</p>
                <Link
                  className="button button-primary"
                  preventScrollReset
                  to={pathFor({ q: null, fromYear: null, toYear: null, genreIds: [] })}
                >
                  Quitar filtros
                </Link>
              </>
            ) : search.state !== "all" ? (
              <>
                <h3>
                  {search.state === "upcoming"
                    ? `Sin próximos juegos de ${context.name}`
                    : `Sin juegos ya lanzados de ${context.name}`}
                </h3>
                <p>Gameómetro no tiene ninguno ahora mismo</p>
                <Link className="button button-primary" preventScrollReset to={pathFor({ state: "all" })}>
                  Ver todos
                </Link>
              </>
            ) : (
              <>
                <h3>Aún sin juegos de {context.name} en Gameómetro</h3>
                <p>Cuando lleguen, aparecerán aquí</p>
                <Link className="button button-primary" to="/plataformas">
                  Ver todas las plataformas
                </Link>
              </>
            )}
          </div>
        ) : (
          <ul aria-label={`Juegos de ${context.name}`} className="release-grid platform-games-grid">
            {shown.items.map((game) => (
              <li className="release-grid-cell" key={game.gameId}>
                <PlatformGameCard game={game} showPlatform={showPlatform} />
              </li>
            ))}
          </ul>
        )}

        {shown === null || shown.items.length === 0 ? null : (
          <div className="releases-footer catalogue-footer">
            <p className="result-count">
              {shown.page.totalPages > 1
                ? `Página ${shown.page.number} de ${shown.page.totalPages}`
                : gamesCount(shown.page.totalItems)}
            </p>
            <CataloguePagination
              page={shown.page}
              pathFor={(page) => platformCataloguePath(context.key, search, { page })}
            />
          </div>
        )}
      </div>
    </section>
  );
}
