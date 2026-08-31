import { Link } from "react-router-dom";

import { releasesSearchPath, type ReleasesSearch, type ReleaseView } from "./releases-search";

const views: ReadonlyArray<{ view: ReleaseView; label: string }> = [
  { view: "recent", label: "Recientes" },
  { view: "upcoming", label: "Próximos" },
];

const linkClass =
  "inline-flex min-h-11 items-center rounded-lg border px-4 py-2 font-semibold focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-200";

type ReleasesViewNavProps = {
  search: ReleasesSearch;
};

/**
 * Switching the evaluated window is navigation, so it stays a link list with the
 * current view exposed through `aria-current` instead of a scripted control.
 */
export function ReleasesViewNav({ search }: ReleasesViewNavProps) {
  return (
    <nav aria-label="Ventana de lanzamientos">
      <ul className="flex flex-wrap gap-3">
        {views.map(({ view, label }) => {
          const isCurrent = search.view === view;
          return (
            <li key={view}>
              <Link
                aria-current={isCurrent ? "page" : undefined}
                className={
                  isCurrent
                    ? `${linkClass} border-cyan-300 bg-cyan-300 text-slate-950`
                    : `${linkClass} border-slate-700 text-slate-100 hover:border-cyan-300`
                }
                to={releasesSearchPath(search, { view, page: 1 })}
              >
                {label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
