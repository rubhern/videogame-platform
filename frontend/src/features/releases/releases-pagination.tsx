import { Link } from "react-router-dom";

import { releasesSearchPath, type ReleasesSearch } from "./releases-search";

const linkClass =
  "inline-flex min-h-11 items-center rounded-lg border border-slate-700 px-4 py-2 font-semibold text-slate-100 hover:border-cyan-300 focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-200";

type ReleasesPaginationProps = {
  search: ReleasesSearch;
  page: { number: number; totalPages: number };
};

export function ReleasesPagination({ search, page }: ReleasesPaginationProps) {
  const isBeyondLastPage = page.totalPages > 0 && page.number > page.totalPages;
  const hasPrevious = page.number > 1;
  const hasNext = page.number < page.totalPages;

  if (isBeyondLastPage) {
    return (
      <nav aria-label="Paginación de lanzamientos" className="mt-8">
        <Link
          className={linkClass}
          to={releasesSearchPath(search, { page: page.totalPages })}
        >
          Ir a la última página
        </Link>
      </nav>
    );
  }

  if (!hasPrevious && !hasNext) {
    return null;
  }

  return (
    <nav
      aria-label="Paginación de lanzamientos"
      className="mt-8 flex flex-wrap items-center gap-4"
    >
      {hasPrevious ? (
        <Link className={linkClass} to={releasesSearchPath(search, { page: page.number - 1 })}>
          Página anterior
        </Link>
      ) : null}
      <p className="text-sm text-slate-300">
        Página {page.number} de {Math.max(page.totalPages, 1)}
      </p>
      {hasNext ? (
        <Link className={linkClass} to={releasesSearchPath(search, { page: page.number + 1 })}>
          Página siguiente
        </Link>
      ) : null}
    </nav>
  );
}
