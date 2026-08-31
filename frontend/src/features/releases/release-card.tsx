import { useState } from "react";
import { Link } from "react-router-dom";

import type { ReleaseListItem } from "./releases-view-model";

const FALLBACK_COVER_URL = "/assets/covers/fallback.svg";
const FALLBACK_COVER_ALTERNATIVE_TEXT = "Carátula oficial no disponible";

const badgeClass =
  "inline-flex items-center rounded-full border border-slate-700 px-3 py-1 text-xs font-medium text-slate-200";

type ReleaseCardProps = {
  item: ReleaseListItem;
};

export function ReleaseCard({ item }: ReleaseCardProps) {
  const [failedCoverUrl, setFailedCoverUrl] = useState<string | null>(null);
  const coverUnavailable =
    item.cover.kind === "provider" && failedCoverUrl === item.cover.url;
  const attribution =
    item.cover.kind === "provider" && !coverUnavailable ? item.cover.attribution : null;

  return (
    <article className="flex h-full flex-col overflow-hidden rounded-xl border border-slate-700 bg-slate-900">
      <img
        alt={coverUnavailable ? FALLBACK_COVER_ALTERNATIVE_TEXT : item.cover.alternativeText}
        className="aspect-3/4 w-full bg-slate-800 object-cover"
        height={400}
        loading="lazy"
        onError={() => {
          if (item.cover.kind === "provider") {
            setFailedCoverUrl(item.cover.url);
          }
        }}
        src={coverUnavailable ? FALLBACK_COVER_URL : item.cover.url}
        width={300}
      />
      <div className="flex flex-1 flex-col gap-2 p-5">
        <h3 className="text-xl font-semibold break-words text-white">{item.title}</h3>
        <p className="text-lg text-cyan-200">{item.date}</p>
        <p className="text-sm text-slate-300">
          {item.platform} · {item.region}
        </p>
        <ul aria-label={`Estado de los datos de ${item.title}`} className="flex flex-wrap gap-2">
          <li className={badgeClass}>{item.status}</li>
          <li className={item.isStale ? `${badgeClass} text-amber-200` : badgeClass}>
            {item.freshness}
          </li>
          {item.review === null ? null : (
            <li className={`${badgeClass} text-amber-200`}>{item.review}</li>
          )}
        </ul>
        <p className="text-sm text-slate-400">Fuente: {item.provenance}</p>
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
          to={`/games/${item.slug}`}
        >
          Ver {item.title}
        </Link>
      </div>
    </article>
  );
}
