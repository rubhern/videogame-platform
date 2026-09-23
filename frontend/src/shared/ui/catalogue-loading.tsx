const PLACEHOLDERS = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11];

export function CatalogueLoading({ message }: { message: string }) {
  return (
    <div className="catalogue-loading">
      <p className="result-count" role="status">
        {message}
      </p>
      <div aria-hidden="true" className="release-grid">
        {PLACEHOLDERS.map((index) => (
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
