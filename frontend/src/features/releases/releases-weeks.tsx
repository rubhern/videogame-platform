import { useNavigate } from "react-router-dom";

import { AppSelect } from "../../shared/ui/app-select";
import { releasesSearchPath, type ReleasesSearch } from "./releases-search";

const options = ([1, 2, 4] as const).map((weeks) => ({
  value: String(weeks),
  label: weeks === 1 ? "1 semana" : `${weeks} semanas`,
  icon: "clock" as const,
}));

export function ReleasesWeeks({ search }: { search: ReleasesSearch }) {
  const navigate = useNavigate();
  return (
    <AppSelect
      className="release-filter-select"
      icon="clock"
      inlineLabel
      label="Periodo:"
      onChange={(value) => {
        const weeks = value === "2" ? 2 : value === "4" ? 4 : 1;
        void navigate(releasesSearchPath(search, { weeks, page: 1 }));
      }}
      options={options}
      value={String(search.weeks)}
    />
  );
}
