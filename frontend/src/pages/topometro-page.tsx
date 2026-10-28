import { useEffect } from "react";
import { useSearchParams } from "react-router-dom";

import { readRankingSource } from "../features/rankings/ranking-search";
import { TopometroRanking } from "../features/rankings/topometro-ranking";

export function TopometroPage() {
  const [searchParams] = useSearchParams();
  const source = readRankingSource(searchParams);

  useEffect(() => {
    const previousTitle = document.title;
    document.title = "TOPómetro · Gameómetro";
    return () => { document.title = previousTitle; };
  }, []);

  return <TopometroRanking source={source} />;
}
