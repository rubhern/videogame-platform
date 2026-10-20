import { AppSelect, type AppSelectOption } from "../../shared/ui/app-select";
import { releaseSortLabels, type ReleaseSort } from "./releases-search";

const options: readonly (AppSelectOption & { value: ReleaseSort })[] = [
  { value: "releaseDate", label: releaseSortLabels.releaseDate, icon: "ascending" },
  { value: "hype", label: releaseSortLabels.hype, icon: "descending" },
  // The glossary's term for a Gameómetro rating (#286); the order reads the community mean.
  { value: "rating", label: releaseSortLabels.rating, icon: "gameometro" },
  // The source label the score readings already use for IGDB's player mean.
  { value: "externalPlayerScore", label: releaseSortLabels.externalPlayerScore, icon: "igdb" },
];

/**
 * **Ordenar por**: the release date, soonest first; Hype, highest first; the Gameómetro community
 * mean, highest first; or the IGDB players' score, highest first. The API orders before it pages, and games without the chosen
 * signal follow in date order rather than counting as zero.
 */
export function ReleasesSort({
  sort,
  onChange,
}: {
  sort: ReleaseSort;
  onChange: (sort: ReleaseSort) => void;
}) {
  return (
    <AppSelect
      className="release-filter-select release-sort-select"
      icon="ascending"
      inlineLabel
      label="Ordenar por"
      onChange={(value) => {
        const chosen = options.find((option) => option.value === value);
        if (chosen !== undefined) onChange(chosen.value);
      }}
      options={options}
      value={sort}
    />
  );
}
