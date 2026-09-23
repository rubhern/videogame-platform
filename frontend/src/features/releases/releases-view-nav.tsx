import { Link } from "react-router-dom";

import { releasesSearchPath, type ReleasesSearch } from "./releases-search";

type ReleasesViewNavProps = {
  search: ReleasesSearch;
};

export function ReleasesViewNav({ search }: ReleasesViewNavProps) {
  const nextView = search.view === "recent" ? "upcoming" : "recent";
  const label = search.view === "recent" ? "Ver próximos" : "Ver recientes";

  return (
    <nav aria-label="Ventana de lanzamientos">
      <Link className="button view-action" to={releasesSearchPath(search, { view: nextView, page: 1 })}>
        {label}
        <span aria-hidden="true">→</span>
      </Link>
    </nav>
  );
}
