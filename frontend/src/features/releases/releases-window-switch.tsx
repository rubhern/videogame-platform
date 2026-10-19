import { Link } from "react-router-dom";

import { releasesSearchPath, type ReleaseView, type ReleasesSearch } from "./releases-search";

const windows: { view: ReleaseView; label: string }[] = [
  { view: "recent", label: "Recientes" },
  { view: "upcoming", label: "Próximos" },
];

/**
 * Lanzamientos is one navigation entry (#341), so the recent and upcoming windows are chosen
 * here, with the same ice selector TOPómetro uses for its Semana/Mes choice. Each option is a
 * link that keeps the platform, region and period selection and returns to the first page.
 */
export function ReleasesWindowSwitch({ search }: { search: ReleasesSearch }) {
  return (
    <nav aria-label="Ventana de lanzamientos" className="featured-granularity releases-window-switch">
      <ul>
        {windows.map((option) => (
          <li key={option.view}>
            <Link
              aria-current={option.view === search.view ? "true" : undefined}
              className="featured-granularity-option"
              to={releasesSearchPath(search, { view: option.view, page: 1 })}
            >
              {option.label}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  );
}
