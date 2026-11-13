import { Link } from "react-router-dom";

type ReleasesPaginationProps = {
  pathFor: (page: number) => string;
  page: { number: number; totalPages: number };
  /** Off when the surrounding bar already states the result total. */
  showPosition?: boolean;
  /** History state each page keeps, such as the day bar's frame. */
  state?: unknown;
  preventScrollReset?: boolean;
};

export function ReleasesPagination({
  pathFor,
  page,
  showPosition = true,
  state,
  preventScrollReset,
}: ReleasesPaginationProps) {
  const isBeyondLastPage = page.totalPages > 0 && page.number > page.totalPages;
  const hasPrevious = page.number > 1;
  const hasNext = page.number < page.totalPages;

  if (isBeyondLastPage) {
    return (
      <nav aria-label="Paginación de lanzamientos" className="mt-8">
        <Link preventScrollReset={preventScrollReset ?? false} state={state}
          className="button"
          to={pathFor(page.totalPages)}
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
        <Link preventScrollReset={preventScrollReset ?? false} state={state}
          className="button view-action view-action-back"
          to={pathFor(page.number - 1)}
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
        <Link preventScrollReset={preventScrollReset ?? false} state={state} className="button view-action" to={pathFor(page.number + 1)}>
          Página siguiente <span aria-hidden="true">→</span>
        </Link>
      ) : null}
    </nav>
  );
}
