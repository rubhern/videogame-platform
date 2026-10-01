import { Link } from "react-router-dom";

import { releasesSearchPath, type ReleasesSearch } from "./releases-search";

type ReleasesPaginationProps = {
  search: ReleasesSearch;
  page: { number: number; totalPages: number };
  /** Off when the surrounding bar already states the result total. */
  showPosition?: boolean;
};

export function ReleasesPagination({
  search,
  page,
  showPosition = true,
}: ReleasesPaginationProps) {
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
        <Link
          className="button view-action view-action-back"
          to={releasesSearchPath(search, { page: page.number - 1 })}
        >
          <span aria-hidden="true">←</span> Página anterior
        </Link>
      ) : null}
      {showPosition ? (
        <p className="text-sm text-muted">
          Página {page.number} de {Math.max(page.totalPages, 1)}
        </p>
      ) : null}
      {hasNext ? (
        <Link className="button view-action" to={releasesSearchPath(search, { page: page.number + 1 })}>
          Página siguiente <span aria-hidden="true">→</span>
        </Link>
      ) : null}
    </nav>
  );
}
