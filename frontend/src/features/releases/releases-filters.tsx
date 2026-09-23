import { Link, useNavigate } from "react-router-dom";

import { platformIcon, regionIcon } from "../../shared/catalogue/taxonomy-icons";
import { MultiSelect, type MultiSelectOption } from "../../shared/ui/multi-select";
import type { SelectIconName } from "../../shared/ui/select-icon";
import {
  hasActiveFilters,
  releasesSearchPath,
  toggleFilterValue,
  type ReleasesSearch,
} from "./releases-search";
import type { ReleaseFilterOption } from "./releases-view-model";

type ReleasesFiltersProps = {
  search: ReleasesSearch;
  platforms: readonly ReleaseFilterOption[];
  regions: readonly ReleaseFilterOption[];
};

function toOptions(
  options: readonly ReleaseFilterOption[],
  iconFor: (option: ReleaseFilterOption) => SelectIconName,
): MultiSelectOption[] {
  return options.map((option) => ({ value: option.id, label: option.name, icon: iconFor(option) }));
}

/**
 * Two compact multi-select dropdowns share one accessible control. Several values inside one
 * dimension combine with OR and the two dimensions combine with AND; no selection means the
 * dimension is unfiltered (`Todas`), which is distinct from selecting the concrete `Worldwide`
 * region. Toggling navigates so the selection stays shareable in the URL.
 */
export function ReleasesFilters({ search, platforms, regions }: ReleasesFiltersProps) {
  const navigate = useNavigate();
  return (
    <section
      aria-labelledby="release-filters-title"
      className="release-filters release-filters-facets"
    >
      <h2 className="sr-only" id="release-filters-title">
        Filtros de lanzamientos
      </h2>
      <div className="release-facet-row">
        <MultiSelect
          allLabel="Todas"
          className="release-filter-select"
          icon="platform"
          label="Plataforma"
          onToggle={(platformId) => {
            void navigate(
              releasesSearchPath(search, {
                platformIds: toggleFilterValue(search.platformIds, platformId),
                page: 1,
              }),
            );
          }}
          options={toOptions(platforms, (platform) => platformIcon(platform.id, platform.name))}
          selected={search.platformIds}
        />
        <MultiSelect
          allLabel="Todas"
          className="release-filter-select"
          icon="region"
          label="Región"
          onToggle={(regionId) => {
            void navigate(
              releasesSearchPath(search, {
                regionIds: toggleFilterValue(search.regionIds, regionId),
                page: 1,
              }),
            );
          }}
          options={toOptions(regions, (region) => regionIcon(region.id))}
          selected={search.regionIds}
        />
        {hasActiveFilters(search) ? (
          <Link
            className="filter-reset"
            to={releasesSearchPath(search, { platformIds: [], regionIds: [], page: 1 })}
          >
            Quitar filtros
          </Link>
        ) : null}
      </div>
    </section>
  );
}
