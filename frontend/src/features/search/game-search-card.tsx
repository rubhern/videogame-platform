import { useState } from "react";
import { Link } from "react-router-dom";

import type { GameSearchResult } from "./game-search-view-model";

const FALLBACK_COVER_URL = "/assets/covers/fallback.svg";
const FALLBACK_COVER_ALTERNATIVE_TEXT = "Carátula oficial no disponible";

const badgeClass =
  "inline-flex items-center rounded-full border border-slate-700 px-3 py-1 text-xs font-medium text-slate-200";

type GameSearchCardProps = {
  result: GameSearchResult;
};

export function GameSearchCard({ result }: GameSearchCardProps) {
  const [failedCoverUrl, setFailedCoverUrl] = useState<string | null>(null);
  const coverUnavailable = result.cover.kind === "provider" && failedCoverUrl === result.cover.url;
  const attribution =
    result.cover.kind === "provider" && !coverUnavailable ? result.cover.attribution : null;

  return (
    <article className="flex h-full flex-col overflow-hidden rounded-xl border border-slate-700 bg-slate-900">
      <img
        alt={coverUnavailable ? FALLBACK_COVER_ALTERNATIVE_TEXT : result.cover.alternativeText}
        className="aspect-3/4 w-full bg-slate-800 object-cover"
        height={400}
        loading="lazy"
        onError={() => {
          if (result.cover.kind === "provider") {
            setFailedCoverUrl(result.cover.url);
          }
        }}
        src={coverUnavailable ? FALLBACK_COVER_URL : result.cover.url}
        width={300}
      />
      <div className="flex flex-1 flex-col gap-2 p-5">
        <h3 className="text-xl font-semibold break-words text-white">{result.title}</h3>
        {result.matchedAlias === null ? null : (
          <p className="text-sm text-slate-300">
            Coincide con el título alternativo{" "}
            <span className="font-semibold text-cyan-200">{result.matchedAlias}</span>
          </p>
        )}
        {result.releaseContext.length === 0 ? (
          <p className="text-sm text-slate-400">Sin lanzamientos registrados en el catálogo.</p>
        ) : (
          <ul
            aria-label={`Lanzamientos de ${result.title}`}
            className="flex flex-col gap-2 text-sm text-slate-300"
          >
            {result.releaseContext.map((context) => (
              <li className="flex flex-wrap items-center gap-2" key={context.key}>
                <span className="text-cyan-200">{context.date}</span>
                <span>
                  {context.platform} · {context.region}
                </span>
                <span className={badgeClass}>{context.status}</span>
                {context.isStale ? (
                  <span className={`${badgeClass} text-amber-200`}>
                    Datos locales desactualizados
                  </span>
                ) : null}
              </li>
            ))}
          </ul>
        )}
        {attribution === null ? (
          <p className="text-sm text-slate-400">Carátula oficial no disponible</p>
        ) : (
          <p className="text-sm text-slate-400">
            Carátula:{" "}
            <a
              className="underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-200"
              href={attribution.sourceUrl}
              rel="noreferrer"
            >
              {attribution.label}
            </a>
          </p>
        )}
        <Link
          className="mt-auto inline-flex min-h-11 items-center self-start rounded-lg bg-cyan-300 px-4 py-2 text-left font-semibold break-words text-slate-950 hover:bg-cyan-200 focus-visible:outline focus-visible:outline-2 focus-visible:outline-cyan-200"
          to={`/games/${result.slug}`}
        >
          Ver {result.title}
        </Link>
      </div>
    </article>
  );
}
