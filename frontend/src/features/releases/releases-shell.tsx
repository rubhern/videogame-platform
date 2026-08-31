import { useEffect, useRef } from "react";
import { Link } from "react-router-dom";

import { ReleaseCard } from "./release-card";
import { ReleasesFilters } from "./releases-filters";
import { ReleasesPagination } from "./releases-pagination";
import { hasActiveFilters, releasesSearchPath, type ReleasesSearch } from "./releases-search";
import { releaseViewTitle, type ReleasesViewModel } from "./releases-view-model";
import { ReleasesViewNav } from "./releases-view-nav";

export type ReleasesShellState =
  | { status: "loading" }
  | {
      status: "ready";
      model: ReleasesViewModel;
      isRefreshing: boolean;
      isPlaceholderData: boolean;
    }
  | { status: "catalogue-not-ready" }
  | { status: "unsupported-filters"; message: string }
  | { status: "error"; message: string; correlationId: string | null };

type ReleasesShellProps = {
  search: ReleasesSearch;
  state: ReleasesShellState;
  onRetry: () => void;
};

const actionClass =
  "inline-flex min-h-11 items-center rounded-lg bg-cyan-300 px-4 py-2 font-semibold text-slate-950 hover:bg-cyan-200 focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-200";
const secondaryActionClass =
  "inline-flex min-h-11 items-center rounded-lg border border-slate-700 px-4 py-2 font-semibold text-slate-100 hover:border-cyan-300 focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-200";

function resultsSummary(model: ReleasesViewModel, isRefreshing: boolean): string {
  if (isRefreshing) {
    return "Actualizando lanzamientos…";
  }
  const { totalItems, number, totalPages } = model.page;
  const count =
    totalItems === 1 ? "1 lanzamiento en la ventana" : `${totalItems} lanzamientos en la ventana`;
  if (totalPages > 0 && number > totalPages) {
    return `${count} · La página ${number} ya no está disponible`;
  }
  return `${count} · Página ${number} de ${Math.max(totalPages, 1)}`;
}

export function ReleasesShell({ search, state, onRetry }: ReleasesShellProps) {
  const resultsHeadingRef = useRef<HTMLHeadingElement>(null);
  const previousPage = useRef(search.page);

  useEffect(() => {
    if (previousPage.current === search.page) {
      return;
    }
    previousPage.current = search.page;
    resultsHeadingRef.current?.focus();
  }, [search.page]);

  const model = state.status === "ready" ? state.model : null;
  const isTransitioning = state.status === "ready" && state.isPlaceholderData;

  return (
    <section aria-labelledby="releases-title" className="mx-auto max-w-5xl px-5 py-12 sm:px-8">
      <p className="text-sm font-semibold uppercase tracking-widest text-cyan-300">
        Descubrimiento de lanzamientos
      </p>
      <h1 id="releases-title" className="mt-3 text-4xl font-bold tracking-tight text-balance break-words text-white">
        {releaseViewTitle(search.view)}
      </h1>
      {model === null || isTransitioning ? null : (
        <p className="mt-4 max-w-2xl text-slate-300">
          {`${model.windowDescription}. ${model.evaluatedOnDescription}.`}
        </p>
      )}

      <div className="mt-8 flex flex-col gap-6">
        <ReleasesViewNav search={search} />
        {model === null ? null : (
          <ReleasesFilters
            platforms={model.platforms}
            regions={model.regions}
            search={search}
          />
        )}
      </div>

      <h2
        className="mt-10 text-2xl font-semibold text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-200"
        ref={resultsHeadingRef}
        tabIndex={-1}
      >
        Resultados
      </h2>

      {state.status === "loading" ? (
        <p className="mt-4 text-slate-200" role="status">
          Cargando lanzamientos…
        </p>
      ) : null}

      {state.status === "catalogue-not-ready" ? (
        <div
          className="mt-4 rounded-lg border border-amber-400/60 bg-amber-950/30 p-5"
          role="alert"
        >
          <h3 className="font-semibold text-amber-100">El catálogo todavía no está disponible</h3>
          <p className="mt-2 text-amber-50">
            Aún no hay una publicación local válida del catálogo. No se consulta ningún proveedor
            externo para completarla.
          </p>
          <button className={`${actionClass} mt-4`} onClick={onRetry} type="button">
            Reintentar
          </button>
        </div>
      ) : null}

      {state.status === "unsupported-filters" ? (
        <div className="mt-4 rounded-lg border border-amber-400/60 bg-amber-950/30 p-5" role="alert">
          <h3 className="font-semibold text-amber-100">Filtro no admitido</h3>
          <p className="mt-2 text-amber-50">{state.message}</p>
          <Link
            className={`${actionClass} mt-4`}
            to={releasesSearchPath(search, { platformId: null, regionId: null, page: 1 })}
          >
            Quitar filtros
          </Link>
        </div>
      ) : null}

      {state.status === "error" ? (
        <div className="mt-4 rounded-lg border border-red-400/60 bg-red-950/30 p-5" role="alert">
          <h3 className="font-semibold text-red-200">No se pudieron cargar los lanzamientos</h3>
          <p className="mt-2 text-red-100">{state.message}</p>
          {state.correlationId === null ? null : (
            <p className="mt-2 text-sm text-red-200">
              Referencia para soporte: {state.correlationId}
            </p>
          )}
          <button className={`${actionClass} mt-4`} onClick={onRetry} type="button">
            Reintentar
          </button>
        </div>
      ) : null}

      {state.status === "ready" && state.isPlaceholderData ? (
        <p className="mt-4 text-slate-200" role="status">
          Cargando lanzamientos para la nueva selección…
        </p>
      ) : null}

      {state.status === "ready" && !state.isPlaceholderData ? (
        <>
          <p className="mt-4 text-slate-300" role="status">
            {resultsSummary(state.model, state.isRefreshing)}
          </p>
          {state.model.staleItemCount > 0 ? (
            <p className="mt-2 text-sm text-amber-200">
              Algunos lanzamientos muestran los últimos datos locales válidos, que ya están
              desactualizados.
            </p>
          ) : null}

          {state.model.items.length === 0 ? (
            <div className="mt-6 rounded-lg border border-slate-700 p-5">
              <p className="text-slate-200">
                {state.model.page.totalItems > 0 &&
                state.model.page.totalPages > 0 &&
                state.model.page.number > state.model.page.totalPages
                  ? "La página solicitada ya no está disponible para estos resultados."
                  : "Ningún lanzamiento del catálogo local coincide con esta ventana y estos filtros."}
              </p>
              {hasActiveFilters(search) && state.model.page.totalItems === 0 ? (
                <Link
                  className={`${secondaryActionClass} mt-4`}
                  to={releasesSearchPath(search, { platformId: null, regionId: null, page: 1 })}
                >
                  Quitar filtros
                </Link>
              ) : null}
            </div>
          ) : (
            <ul
              aria-label={releaseViewTitle(state.model.view)}
              className="mt-6 grid gap-5 sm:grid-cols-2 lg:grid-cols-3"
            >
              {state.model.items.map((item) => (
                <li className="min-w-0" key={item.releaseId}>
                  <ReleaseCard item={item} />
                </li>
              ))}
            </ul>
          )}

          <ReleasesPagination page={state.model.page} search={search} />
        </>
      ) : null}
    </section>
  );
}
