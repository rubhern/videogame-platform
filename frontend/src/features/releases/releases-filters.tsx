import { Link, useNavigate } from "react-router-dom";

import { AppSelect } from "../../shared/ui/app-select";
import type { SelectIconName } from "../../shared/ui/select-icon";
import { hasActiveFilters, releasesSearchPath, type ReleasesSearch } from "./releases-search";
import type { ReleaseFilterOption } from "./releases-view-model";

type ReleasesFiltersProps = {
  search: ReleasesSearch;
  platforms: readonly ReleaseFilterOption[];
  regions: readonly ReleaseFilterOption[];
};

type FilterSelectProps = {
  label: string;
  currentId: string | null;
  options: readonly ReleaseFilterOption[];
  allLabel: string;
  toPath: (id: string | null) => string;
};

function platformIcon(option: ReleaseFilterOption): SelectIconName {
  const identity = `${option.id} ${option.name}`.toLocaleLowerCase("es");
  if (identity.includes("switch") || identity.includes("nintendo")) return "nintendo-switch";
  if (identity.includes("playstation") || identity.includes("ps5")) return "playstation";
  if (identity.includes("xbox")) return "xbox";
  if (identity.includes("windows") || identity.includes("pc")) return "windows";
  return "platform";
}

function ReleaseFilterSelect({ label, currentId, options, allLabel, toPath }: FilterSelectProps) {
  const navigate = useNavigate();
  const icon = label === "Plataforma" ? "platform" : "region";
  return (
    <AppSelect
      className="release-filter-select"
      icon={icon}
      inlineLabel
      label={`${label}:`}
      onChange={(value) => { void navigate(toPath(value || null)); }}
      options={[
        { value: "", label: allLabel, icon },
        ...options.map((option) => ({
          value: option.id,
          label: option.name,
          icon: label === "Plataforma" ? platformIcon(option) : icon,
        })),
      ]}
      value={currentId ?? ""}
    />
  );
}

export function ReleasesFilters({ search, platforms, regions }: ReleasesFiltersProps) {
  return (
    <section aria-labelledby="release-filters-title" className="release-filters release-filters-selects">
      <h2 className="sr-only" id="release-filters-title">
        Filtros de lanzamientos
      </h2>
      <div className="release-select-row">
        <ReleaseFilterSelect
          allLabel="Todas"
          currentId={search.platformId}
          label="Plataforma"
          options={platforms}
          toPath={(platformId) => releasesSearchPath(search, { platformId, page: 1 })}
        />
        <ReleaseFilterSelect
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
