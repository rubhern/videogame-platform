import { Link } from "react-router-dom";

import { hasActiveFilters, releasesSearchPath, type ReleasesSearch } from "./releases-search";
import type { ReleaseFilterOption } from "./releases-view-model";

type ReleasesFiltersProps = {
  search: ReleasesSearch;
  platforms: readonly ReleaseFilterOption[];
  regions: readonly ReleaseFilterOption[];
};

type FilterGroupProps = {
  label: string;
  currentId: string | null;
  options: readonly ReleaseFilterOption[];
  allLabel: string;
  toPath: (id: string | null) => string;
};

function FilterGroup({ label, currentId, options, allLabel, toPath }: FilterGroupProps) {
  return (
    <div className="filter-group">
      <span className="filter-label">{label}</span>
      <ul aria-label={`Filtrar por ${label.toLocaleLowerCase("es")}`} className="filter-options">
        <li>
          <Link
            aria-current={currentId === null ? "page" : undefined}
            className="filter-chip"
            to={toPath(null)}
          >
            {allLabel}
          </Link>
        </li>
        {options.map((option) => (
          <li key={option.id}>
            <Link
              aria-current={currentId === option.id ? "page" : undefined}
              className="filter-chip"
              to={toPath(option.id)}
            >
              {option.name}
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function ReleasesFilters({ search, platforms, regions }: ReleasesFiltersProps) {
  return (
    <section aria-labelledby="release-filters-title" className="release-filters">
      <h2 className="sr-only" id="release-filters-title">
        Filtros de lanzamientos
      </h2>
      <div className="filter-rail">
        <FilterGroup
          allLabel="Todas"
          currentId={search.platformId}
          label="Plataforma"
          options={platforms}
          toPath={(platformId) => releasesSearchPath(search, { platformId, page: 1 })}
        />
        <span className="filter-divider" aria-hidden="true" />
        <FilterGroup
          allLabel="Todas"
          currentId={search.regionId}
          label="Región"
          options={regions}
          toPath={(regionId) => releasesSearchPath(search, { regionId, page: 1 })}
        />
        {hasActiveFilters(search) ? (
          <Link
            className="filter-reset"
            to={releasesSearchPath(search, { platformId: null, regionId: null, page: 1 })}
          >
            Quitar filtros
          </Link>
        ) : null}
      </div>
    </section>
  );
}
