import { Link } from "react-router-dom";

import { CatalogueCover } from "../../shared/ui/catalogue-cover";
import type { GameSearchResult } from "./game-search-view-model";

type GameSearchCardProps = {
  result: GameSearchResult;
};

export function GameSearchCard({ result }: GameSearchCardProps) {
  const gamePath = `/games/${result.slug}`;

  return (
    <article className="catalogue-card">
      <CatalogueCover cover={result.cover} to={gamePath} />
      <div className="card-body">
        <h3 className="card-title">
          <Link tabIndex={-1} to={gamePath}>{result.title}</Link>
        </h3>
        {result.matchedAlias === null ? null : (
          <p className="card-alias">
            Coincide con el título alternativo <span>{result.matchedAlias}</span>
          </p>
        )}
        {result.releaseContext.length === 0 ? (
          <p className="card-platform">Sin lanzamientos registrados.</p>
        ) : (
          <ul aria-label={`Lanzamientos de ${result.title}`} className="search-release-context">
            {result.releaseContext.map((context) => (
              <li key={context.key}>
                <span className="search-release-date">{context.date}</span>
                <span>
                  {context.platform} · {context.region}
                </span>
                <span className="badge">{context.status}</span>
                {context.isStale ? (
                  <span className="badge badge-warning">Datos locales desactualizados</span>
                ) : null}
              </li>
            ))}
          </ul>
        )}
        <Link className="card-action" to={gamePath}>
          <span className="sr-only">Ver {result.title}</span>
          <span aria-hidden="true">Ver ficha →</span>
        </Link>
      </div>
    </article>
  );
}
