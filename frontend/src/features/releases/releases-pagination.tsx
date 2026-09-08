import { Link } from "react-router-dom";

import { releasesSearchPath, type ReleasesSearch } from "./releases-search";

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
          className="button"
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
      className="pagination"
    >
      {hasPrevious ? (
        <Link className="button" to={releasesSearchPath(search, { page: page.number - 1 })}>
          Página anterior
        </Link>
      ) : null}
      <p className="text-sm text-muted">
        Página {page.number} de {Math.max(page.totalPages, 1)}
      </p>
      {hasNext ? (
        <Link className="button" to={releasesSearchPath(search, { page: page.number + 1 })}>
          Página siguiente
        </Link>
      ) : null}
    </nav>
  );
}
