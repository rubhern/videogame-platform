/**
 * Detail-shaped placeholder for the public game page.
 *
 * <p>It mirrors the real composition — the cover above its readings, beside the editorial opening
 * and structured facts — so the arriving record lands in the same frame instead
 * of replacing a catalogue grid. The live status belongs to the caller; the placeholders stay silent.
 */
export function GameDetailsLoading({ message }: { message: string }) {
  return (
    <div className="game-details-loading">
      <p className="result-count" role="status">
        {message}
      </p>
      <div aria-hidden="true" className="game-detail-layout">
        <div className="game-detail-heading">
          <span className="skeleton detail-skeleton-kicker" />
          <span className="skeleton detail-skeleton-title" />
          <span className="skeleton detail-skeleton-aliases" />
        </div>
        <div className="game-detail-identity">
          <div className="game-artwork">
            <span className="skeleton skeleton-cover" />
          </div>
          <div className="game-scores">
            <span className="skeleton detail-skeleton-score" />
            <span className="skeleton detail-skeleton-rating" />
          </div>
        </div>
        <span className="skeleton detail-skeleton-summary" />
        <span className="skeleton detail-skeleton-information game-detail-facts" />
      </div>
    </div>
  );
}
