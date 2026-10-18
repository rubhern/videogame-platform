const DEFAULT_PLACEHOLDERS = 12;

type CatalogueLoadingProps = {
  message: string;
  /** Matches the page the placeholders stand in for, so arriving results keep the same frame. */
  placeholders?: number;
  /** The release windows' cover grid or the search's catalogue index. */
  shape?: "grid" | "index";
};

export function CatalogueLoading({
  message,
  placeholders = DEFAULT_PLACEHOLDERS,
  shape = "grid",
}: CatalogueLoadingProps) {
  return (
    <div className="catalogue-loading">
      <p className="result-count" role="status">
        {message}
      </p>
      <div aria-hidden="true" className={shape === "grid" ? "release-grid" : "search-index"}>
        {Array.from({ length: placeholders }, (_, index) => (
          <div className={`loading-card loading-card-${shape}`} key={index}>
            <div className="skeleton skeleton-cover" />
            <div className="loading-card-lines">
              <div className="skeleton skeleton-meta skeleton-meta-short" />
              <div className="skeleton skeleton-title" />
              <div className="skeleton skeleton-meta" />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
