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
import { toggleFilterValue, type ReleasesSearch } from "./releases-search";
import type { ReleaseFilterOption } from "./releases-view-model";

/** A change of the facets; every other choice of the screen stays as it is. */
export type FacetChange = Partial<Pick<ReleasesSearch, "platformIds" | "regionIds">>;

type ReleasesFiltersProps = {
  /** The regions the URL selects; platforms come resolved in `platformFilter`. */
  regionIds: readonly string[];
  /** The screen's own path for a facet change, which also returns it to its first page. */
  pathFor: (change: FacetChange) => string;
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
 * region. Toggling navigates so the selection stays shareable in the URL; the release explorer and
 * the release calendar (#250) each name their own path.
 *
 * Post-MVP (#295), **Plataforma** always shows the platforms that actually apply: with the
 * preferred platforms on it shows them, summarized as `Mis plataformas`, and choosing a platform
 * starts a manual selection from them, turning the preferences off. Turning them on again in
 * **Mi cuenta** replaces the manual selection, so the two never intersect.
 */
export function ReleasesFilters({
  regionIds,
  pathFor,
  platforms,
  regions,
  state,
  interests,
  platformFilter,
}: ReleasesFiltersProps) {
  const navigate = useNavigate();
  const interestsSwitch = useInterestsSwitch();
  const go = (change: FacetChange) => {
    void navigate(pathFor(change), {
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
          onToggle={(regionId) => go({ regionIds: toggleFilterValue(regionIds, regionId) })}
          options={toOptions(regions, (region) => regionIcon(region.id, region.name))}
          selected={regionIds}
        />
        {hasEffectiveFilters({ regionIds }, platformFilter) ? (
          <Link
            preventScrollReset
            className="filter-reset"
            onClick={() => setAside(clearedPlatforms(interests))}
            state={state}
            to={pathFor({ platformIds: [], regionIds: [] })}
          >
            Quitar filtros
          </Link>
        ) : null}
      </div>
    </section>
  );
}
