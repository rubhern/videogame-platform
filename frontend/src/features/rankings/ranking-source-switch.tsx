import { Link } from "react-router-dom";

import { BrandMark } from "../../shared/brand/brand-logo";
import { rankingPath, type RankingSource } from "./ranking-search";

const SOURCES: readonly { source: RankingSource; label: string }[] = [
  { source: "igdb", label: "Jugadores IGDB" },
  { source: "gameometro", label: "Comunidad Gameómetro" },
];

/**
 * The one choice TOPómetro offers: whose ratings rank the games. Each source is a native link, so
 * the choice lives in the URL and in history, carries its source's own mark and is never mixed
 * with the other. On a platform's TOPómetro each source keeps the platform.
 */
export function RankingSourceSwitch({ source, platformKey }: { source: RankingSource; platformKey?: string }) {
  return (
    <nav aria-label="Puntuaciones del TOPómetro" className="ranking-sources">
      <ul className="ranking-source-list">
        {SOURCES.map((entry) => (
          <li key={entry.source}>
            <Link
              aria-current={entry.source === source ? "true" : undefined}
              className="ranking-source"
              data-source={entry.source}
              preventScrollReset
              to={rankingPath(entry.source, platformKey)}
            >
              {entry.source === "igdb" ? (
                <img alt="" className="ranking-source-mark" height="22" src="/assets/providers/igdb.svg" width="44" />
              ) : (
                <BrandMark className="ranking-source-mark" />
              )}
              <span>{entry.label}</span>
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  );
}
