import { queryOptions } from "@tanstack/react-query";

import type { paths } from "../../shared/api/generated/schema";
import { productApiClient } from "../../shared/api/product-api-client";
import { toCover, type GameSearchCover } from "../search/game-search-view-model";

export type HomeRankingKind = paths["/home-rankings/{kind}"]["get"]["parameters"]["path"]["kind"];
export type HomeRankedGame = Awaited<ReturnType<typeof readHomeRanking>>[number];
export const HOME_ACTIVITY_KEY = ["home-rankings"] as const;

export class HomeRankingError extends Error {
  constructor(readonly code: string | null, readonly correlationId: string | null) {
    super("Home ranking unavailable");
  }
}

async function readHomeRanking(kind: HomeRankingKind, signal: AbortSignal) {
  const { data, error, response } = await productApiClient.GET("/home-rankings/{kind}", {
    params: { path: { kind } },
    credentials: "omit",
    signal,
  });
  if (data === undefined) {
    throw new HomeRankingError(error?.code ?? null, response.headers.get("X-Correlation-ID"));
  }
  return data.items.map(({ primaryCover, ...game }) => ({
    ...game,
    cover: "attribution" in primaryCover
      ? toCover(primaryCover)
      : { kind: "fallback", url: primaryCover.url, alternativeText: primaryCover.alternativeText } satisfies GameSearchCover,
  }));
}

export function homeRankingOptions(kind: HomeRankingKind) {
  return queryOptions({
    queryKey: [...HOME_ACTIVITY_KEY, kind],
    queryFn: ({ signal }) => readHomeRanking(kind, signal),
    staleTime: 30_000,
    retry: false,
  });
}

export async function reportGameDetailVisit(gameId: string, visitId: string) {
  await productApiClient.POST("/game-detail-visits", {
    body: { gameId, visitId },
    credentials: "omit",
    keepalive: true,
  });
}
