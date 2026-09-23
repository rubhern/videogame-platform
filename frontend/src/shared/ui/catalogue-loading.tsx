const DEFAULT_PLACEHOLDERS = 12;

type CatalogueLoadingProps = {
  message: string;
  /** Matches the page the placeholders stand in for, so arriving results keep the grid shape. */
  placeholders?: number;
};

export function CatalogueLoading({ message, placeholders = DEFAULT_PLACEHOLDERS }: CatalogueLoadingProps) {
  return (
    <div className="catalogue-loading">
      <p className="result-count" role="status">
        {message}
      </p>
      <div aria-hidden="true" className="release-grid">
        {Array.from({ length: placeholders }, (_, index) => (
          <div className="loading-card" key={index}>
            <div className="skeleton skeleton-cover" />
            <div className="skeleton skeleton-title" />
            <div className="skeleton skeleton-meta" />
            <div className="skeleton skeleton-meta skeleton-meta-short" />
          </div>
        ))}
      </div>
    </div>
  );
}
