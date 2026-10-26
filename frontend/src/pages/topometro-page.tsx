import { useEffect } from "react";
import { useSearchParams } from "react-router-dom";

import { GameRankingApiError } from "../features/rankings/game-ranking-api";
import { toRankingView } from "../features/rankings/game-ranking-view-model";
import { readRankingSource } from "../features/rankings/ranking-search";
import { TopometroShell, type TopometroState } from "../features/rankings/topometro-shell";
import { useGameRankingQuery } from "../features/rankings/use-game-ranking-query";

function correlationId(error: unknown): string | null {
  return error instanceof GameRankingApiError ? error.correlationId : null;
}

export function TopometroPage() {
  const [searchParams] = useSearchParams();
  const source = readRankingSource(searchParams);
  const query = useGameRankingQuery(source);

  useEffect(() => {
    const previousTitle = document.title;
    document.title = "TOPómetro · Gameómetro";
    return () => { document.title = previousTitle; };
  }, []);

  let state: TopometroState;
  if (query.isPending) {
    state = { status: "loading" };
  } else if (query.isError && query.data === undefined) {
    state = query.error instanceof GameRankingApiError && query.error.code === "CATALOGUE_NOT_READY"
      ? { status: "catalogue-not-ready" }
      : { status: "error", correlationId: correlationId(query.error) };
  } else {
    const last = query.data?.pages.at(-1)?.page;
    state = {
      status: "ready",
      view: toRankingView(query.data?.pages ?? [], source),
      next: query.hasNextPage && last !== undefined
        ? { from: last.number * last.size + 1, to: Math.min((last.number + 1) * last.size, last.totalItems) }
        : null,
      loadingMore: query.isFetchingNextPage,
      moreFailed: query.isFetchNextPageError ? { correlationId: correlationId(query.error) } : null,
    };
  }

  return (
    <TopometroShell
      onLoadMore={() => {
        // A page already being read is never cancelled and requested again.
        void query.fetchNextPage({ cancelRefetch: false });
      }}
      onRetry={() => {
        void query.refetch();
      }}
      source={source}
      state={state}
    />
  );
}
