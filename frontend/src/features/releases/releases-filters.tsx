import { Link, useNavigate } from "react-router-dom";

import { hasActiveFilters, releasesSearchPath, type ReleasesSearch } from "./releases-search";
import type { ReleaseFilterOption } from "./releases-view-model";

const controlClass =
  "min-h-11 w-full rounded-lg border border-slate-700 bg-slate-900 px-3 py-2 text-slate-100 focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-200";

type ReleasesFiltersProps = {
  search: ReleasesSearch;
  platforms: readonly ReleaseFilterOption[];
  regions: readonly ReleaseFilterOption[];
};

export function ReleasesFilters({ search, platforms, regions }: ReleasesFiltersProps) {
  const navigate = useNavigate();

  return (
    <fieldset className="rounded-xl border border-slate-800 p-5">
      <legend className="px-2 text-sm font-semibold uppercase tracking-widest text-cyan-300">
        Filtros
      </legend>
      <p className="sr-only">Los filtros se aplican al cambiar su valor.</p>
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="flex flex-col gap-2">
          <label className="text-sm text-slate-300" htmlFor="releases-platform-filter">
            Plataforma
          </label>
          <select
            className={controlClass}
            id="releases-platform-filter"
            onChange={(event) => {
              const value = event.target.value;
              void navigate(
                releasesSearchPath(search, {
                  platformId: value === "" ? null : value,
                  page: 1,
                }),
              );
            }}
            value={search.platformId ?? ""}
          >
            <option value="">Todas las plataformas</option>
            {platforms.map((platform) => (
              <option key={platform.id} value={platform.id}>
                {platform.name}
              </option>
            ))}
          </select>
        </div>
        <div className="flex flex-col gap-2">
          <label className="text-sm text-slate-300" htmlFor="releases-region-filter">
            Región
          </label>
          <select
            className={controlClass}
            id="releases-region-filter"
            onChange={(event) => {
              const value = event.target.value;
              void navigate(
                releasesSearchPath(search, {
                  regionId: value === "" ? null : value,
                  page: 1,
                }),
              );
            }}
            value={search.regionId ?? ""}
          >
            <option value="">Todas las regiones</option>
            {regions.map((region) => (
              <option key={region.id} value={region.id}>
                {region.name}
              </option>
            ))}
          </select>
        </div>
      </div>
      {hasActiveFilters(search) ? (
        <Link
          className="mt-4 inline-flex min-h-11 items-center rounded-lg border border-slate-700 px-4 py-2 font-semibold text-slate-100 hover:border-cyan-300 focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-200"
          to={releasesSearchPath(search, { platformId: null, regionId: null, page: 1 })}
        >
          Quitar filtros
        </Link>
      ) : null}
    </fieldset>
  );
}
