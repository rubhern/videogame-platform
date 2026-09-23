import { Link } from "react-router-dom";

import { gameSearchPath, type GameSearchParams } from "./game-search-params";

type GameSearchPaginationProps = {
  params: GameSearchParams;
  page: { number: number; totalPages: number };
};

export function GameSearchPagination({ params, page }: GameSearchPaginationProps) {
  const isBeyondLastPage = page.totalPages > 0 && page.number > page.totalPages;
  const hasPrevious = page.number > 1;
  const hasNext = page.number < page.totalPages;

  if (isBeyondLastPage) {
    return (
      <nav aria-label="Paginación de resultados" className="mt-8">
        <Link className="button" to={gameSearchPath(params, { page: page.totalPages })}>
          Ir a la última página
        </Link>
      </nav>
    );
  }

  if (!hasPrevious && !hasNext) {
    return null;
  }

  return (
    <nav aria-label="Paginación de resultados" className="pagination search-pagination">
      <p>
        Página {page.number} de {Math.max(page.totalPages, 1)}
      </p>
      {hasPrevious ? (
        <Link className="button view-action view-action-back" to={gameSearchPath(params, { page: page.number - 1 })}>
          <span aria-hidden="true">←</span> Página anterior
        </Link>
      ) : null}
      {hasNext ? (
        <Link className="button view-action" to={gameSearchPath(params, { page: page.number + 1 })}>
          Página siguiente <span aria-hidden="true">→</span>
        </Link>
      ) : null}
    </nav>
  );
}
