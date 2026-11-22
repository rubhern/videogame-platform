import type { components, operations } from "../../shared/api/generated/schema";
import { productApiClient } from "../../shared/api/product-api-client";

export const LIBRARY_KEY = ["me", "library"] as const;
export const LIBRARY_STATES_KEY = [...LIBRARY_KEY, "states"] as const;
export const LIBRARY_PAGE_SIZE = 20;

export type LibraryState = components["schemas"]["LibraryState"];
export type LibraryPage = components["schemas"]["LibraryPage"];
export type LibraryGame = components["schemas"]["LibraryGame"];
type LibraryQuery = NonNullable<operations["listMyLibrary"]["parameters"]["query"]>;
type Problem = components["schemas"]["Problem"];

export const LIBRARY_STATES: readonly LibraryState[] = ["wished", "backlog", "playing", "completed", "abandoned"];

/** Spanish names from the product glossary; the contract values stay machine-facing. */
export const libraryStateLabels: Readonly<Record<LibraryState, string>> = {
  wished: "Deseado",
  backlog: "Pendiente",
  playing: "Jugando",
  completed: "Completado",
  abandoned: "Abandonado",
};

/** The `estado` URL value of each state on Mis juegos. */
export const libraryStateSlugs: Readonly<Record<LibraryState, string>> = {
  wished: "deseado",
  backlog: "pendiente",
  playing: "jugando",
  completed: "completado",
  abandoned: "abandonado",
};

export function libraryStateFromSlug(slug: string | null): LibraryState | undefined {
  return LIBRARY_STATES.find((state) => libraryStateSlugs[state] === slug);
}

export class LibraryError extends Error {
  constructor(readonly status: number, readonly correlationId: string | null) {
    super("The library request failed.");
  }
}

function requestFailure(response: Response, error: Problem | undefined) {
  return new LibraryError(response.status, error?.correlationId ?? response.headers.get("X-Correlation-ID"));
}

export async function listLibrary(query: LibraryQuery, signal: AbortSignal) {
  const { data, error, response } = await productApiClient.GET("/me/library", {
    params: { query }, cache: "no-store", signal,
  });
  if (data !== undefined) return data;
  throw requestFailure(response, error);
}

/** The state of each game in the bounded set; absent games have none. */
export async function readGameStates(gameIds: readonly string[], signal: AbortSignal) {
  const { data, error, response } = await productApiClient.GET("/me/library/states", {
    params: { query: { gameIds: [...gameIds] } }, cache: "no-store", signal,
  });
  if (data === undefined) throw requestFailure(response, error);
  return new Map(data.entries.map((entry) => [entry.gameId, entry.state]));
}

export type LibraryCommand = { gameId: string; csrfToken: string; state: LibraryState | null };

/** A null state removes the game from the library. */
export async function changeGameState({ gameId, csrfToken, state }: LibraryCommand) {
  const params = { path: { gameId }, header: { "X-CSRF-Token": csrfToken } };
  const result = state === null
    ? await productApiClient.DELETE("/me/library/games/{gameId}", { params, cache: "no-store" })
    : await productApiClient.PUT("/me/library/games/{gameId}", { params, body: { state }, cache: "no-store" });
  if (result.response.status !== 204) throw requestFailure(result.response, result.error);
}

/** Marks one bounded batch of rated games without a state Completado; returns how many. */
export async function completeRatedGames(csrfToken: string) {
  const { data, error, response } = await productApiClient.POST("/me/library/rated-games/completion", {
    params: { header: { "X-CSRF-Token": csrfToken } }, cache: "no-store",
  });
  if (data !== undefined) return data.completed;
  throw requestFailure(response, error);
}
