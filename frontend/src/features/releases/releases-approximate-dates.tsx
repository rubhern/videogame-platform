import { useNavigate } from "react-router-dom";

import { releasesSearchPath, type ReleasesSearch } from "./releases-search";

/**
 * Upcoming discovery lists exact release days by default. This native checkbox opts into
 * month, quarter, year and to-be-confirmed dates, which keep their own precision on every
 * card. The choice lives in the URL and returns to the first page, like the other filters.
 */
export function ReleasesApproximateDates({ search }: { search: ReleasesSearch }) {
  const navigate = useNavigate();
  return (
    <label className="release-approximate-option">
      <input
        checked={search.includeApproximateDates}
        onChange={(event) => {
          void navigate(
            releasesSearchPath(search, {
              includeApproximateDates: event.currentTarget.checked,
              page: 1,
            }),
          );
        }}
        type="checkbox"
      />
      <span>Incluir fechas aproximadas</span>
    </label>
  );
}
