/**
 * Detail-shaped placeholder for the public game page.
 *
 * <p>It mirrors the real composition — cover column, title, context panel and the two
 * bottom panels — so the arriving record lands in the same frame instead of replacing a
 * catalogue grid. The live status belongs to the caller; the placeholders stay silent.
 */
export function GameDetailsLoading({ message }: { message: string }) {
  return (
    <div className="game-details-loading">
      <p className="result-count" role="status">
        {message}
      </p>
      <div aria-hidden="true" className="game-detail-layout">
        <div className="game-detail-heading">
          <span className="skeleton detail-skeleton-title" />
          <span className="skeleton detail-skeleton-aliases" />
        </div>
        <div className="game-artwork">
          <span className="skeleton skeleton-cover" />
        </div>
        <div className="game-context-selectors">
          <span className="skeleton detail-skeleton-pill" />
          <span className="skeleton detail-skeleton-pill" />
          <span className="skeleton detail-skeleton-pill" />
        </div>
        <div className="game-information-panel detail-skeleton-panel">
          <span className="skeleton detail-skeleton-rows" />
          <span className="skeleton detail-skeleton-summary" />
        </div>
        <div className="game-rating-row">
          <span className="skeleton detail-skeleton-score" />
          <span className="skeleton detail-skeleton-score" />
        </div>
      </div>
    </div>
  );
}
