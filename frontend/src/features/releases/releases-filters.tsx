import { Link, useNavigate } from "react-router-dom";

import { platformIcon, regionIcon } from "../../shared/catalogue/taxonomy-icons";
import { MultiSelect, type MultiSelectOption } from "../../shared/ui/multi-select";
import type { SelectIconName } from "../../shared/ui/select-icon";
import {
  clearedPlatforms,
  togglePlatformSelection,
  type Interests,
  type PlatformFilter,
  type PlatformSelectionChange,
} from "../platform-preferences/interests";
import { useInterestsSwitch } from "../platform-preferences/interests-switch";
import { hasEffectiveFilters } from "./release-filter-paths";
import { releasesSearchPath, toggleFilterValue, type ReleasesSearch } from "./releases-search";
import type { ReleaseFilterOption } from "./releases-view-model";

type ReleasesFiltersProps = {
  search: ReleasesSearch;
  /** History state every change keeps, such as the day bar's frame. */
  state?: unknown;
  platforms: readonly ReleaseFilterOption[];
  regions: readonly ReleaseFilterOption[];
  /** The visitor's preferred platforms and the platform filter they resolve to (#295). */
  interests: Interests;
  platformFilter: PlatformFilter;
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
 *
 * Post-MVP (#295), **Plataforma** always shows the platforms that actually apply: with the
 * preferred platforms on it shows them, summarized as `Mis plataformas`, and choosing a platform
 * starts a manual selection from them, turning the preferences off. Turning them on again in
 * **Mi cuenta** replaces the manual selection, so the two never intersect.
 */
export function ReleasesFilters({
  search,
  platforms,
  regions,
  state,
  interests,
  platformFilter,
}: ReleasesFiltersProps) {
  const navigate = useNavigate();
  const interestsSwitch = useInterestsSwitch();
  const go = (change: Partial<ReleasesSearch>) => {
    void navigate(releasesSearchPath(search, { ...change, page: 1 }), {
      preventScrollReset: true,
      state,
    });
  };
  const setAside = (change: PlatformSelectionChange) => {
    if (change.interestsEnabled !== null) interestsSwitch.set(change.interestsEnabled);
  };
  const platformsChange = (change: PlatformSelectionChange) => {
    setAside(change);
    go({ platformIds: change.platformIds });
  };
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
          onToggle={(platformId) =>
            platformsChange(togglePlatformSelection(platformFilter, platformId, interests))
          }
          options={toOptions(platforms, (platform) => platformIcon(platform.id, platform.name))}
          selected={platformFilter.platformIds}
          {...(platformFilter.mode === "on"
            ? { summarize: () => "Mis plataformas" }
            : {})}
        />
        <MultiSelect
          allLabel="Todas"
          className="release-filter-select"
          icon="region"
          label="Región"
          onToggle={(regionId) => go({ regionIds: toggleFilterValue(search.regionIds, regionId) })}
          options={toOptions(regions, (region) => regionIcon(region.id, region.name))}
          selected={search.regionIds}
        />
        {hasEffectiveFilters(search, platformFilter) ? (
          <Link
            preventScrollReset
            className="filter-reset"
            onClick={() => setAside(clearedPlatforms(interests))}
            state={state}
            to={releasesSearchPath(search, { platformIds: [], regionIds: [], page: 1 })}
          >
            Quitar filtros
          </Link>
        ) : null}
      </div>
    </section>
  );
}
