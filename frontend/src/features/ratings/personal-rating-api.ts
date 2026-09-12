import type { components } from "../../shared/api/generated/schema";
import {
  productApiClient,
  type ProductApiClient,
} from "../../shared/api/product-api-client";

export type PersonalRating = components["schemas"]["PersonalRating"];
export type RatingStatistics =
  components["schemas"]["AvailableRatingStatistics"];
type Problem = components["schemas"]["Problem"];

/**
 * Product-facing outcome of a rejected or failed rating command.
 *
 * <p>`kind` is the closed UI vocabulary; `code` keeps the stable machine-facing contract value
 * when the server returned one. `ambiguous` means the request may or may not have been applied
 * (transport failure before a response), so the caller must never retry it automatically.
 */
export type RatingFailureKind =
  | "authentication"
  | "csrf"
  | "conflict"
  | "validation"
  | "ineligible"
  | "rate-limited"
  | "unavailable"
  | "ambiguous";

export class RatingCommandError extends Error {
  constructor(
    readonly kind: RatingFailureKind,
    readonly code: Problem["code"] | null,
    readonly correlationId: string | null,
  ) {
    super(`The rating command failed (${kind}).`);
  }
}

/** Exactly one intent precondition, as the contract requires. */
export type RatingPrecondition =
  | { create: true }
  | { create: false; entityTag: string };

type CommandContext = {
  gameId: string;
  csrfToken: string;
};

function failure(
  status: number,
  problem: Problem | undefined,
  response: Response,
): RatingCommandError {
  const code = problem?.code ?? null;
  const correlationId =
    problem?.correlationId ?? response.headers.get("X-Correlation-ID");
  if (status === 401) {
    return new RatingCommandError("authentication", code, correlationId);
  }
  if (status === 403) {
    return new RatingCommandError("csrf", code, correlationId);
  }
  // The rating changed or disappeared in another session: the local ETag is no longer current.
  if (status === 412 || status === 404) {
    return new RatingCommandError("conflict", code, correlationId);
  }
  if (status === 422) {
    return new RatingCommandError(
      code === "RATING_NOT_ELIGIBLE" || code === "RELEASE_DATA_REVIEW_REQUIRED"
        ? "ineligible"
        : "validation",
      code,
      correlationId,
    );
  }
  if (status === 429) {
    return new RatingCommandError("rate-limited", code, correlationId);
  }
  return new RatingCommandError("unavailable", code, correlationId);
}

/** Reads the current user's rating for one game, or null when the user has none. */
export async function getMyRating(
  gameId: string,
  client: ProductApiClient = productApiClient,
  signal?: AbortSignal,
): Promise<PersonalRating | null> {
  const { data, error, response } = await client
    .GET("/me/ratings/{gameId}", {
      params: { path: { gameId } },
      ...(signal ? { signal } : {}),
    })
    .catch(() => {
      throw new RatingCommandError("ambiguous", null, null);
    });
  if (data !== undefined) return data;
  if (response.status === 404) return null;
  throw failure(response.status, error, response);
}

export type RatingWriteResult = components["schemas"]["RatingWriteResult"];

/** Creates (`If-None-Match: *`) or updates (`If-Match`) the current user's rating. */
export async function putMyRating(
  { gameId, csrfToken }: CommandContext,
  value: number,
  precondition: RatingPrecondition,
  client: ProductApiClient = productApiClient,
): Promise<RatingWriteResult> {
  const { data, error, response } = await client
    .PUT("/me/ratings/{gameId}", {
      params: {
        path: { gameId },
        header: {
          "X-CSRF-Token": csrfToken,
          ...(precondition.create
            ? { "If-None-Match": "*" as const }
            : { "If-Match": precondition.entityTag }),
        },
      },
      body: { value },
    })
    .catch(() => {
      throw new RatingCommandError("ambiguous", null, null);
    });
  if (data !== undefined) return data;
  throw failure(response.status, error, response);
}

/** Deletes the current user's rating with its current strong entity tag. */
export async function deleteMyRating(
  { gameId, csrfToken }: CommandContext,
  entityTag: string,
  client: ProductApiClient = productApiClient,
): Promise<RatingStatistics> {
  const { data, error, response } = await client
    .DELETE("/me/ratings/{gameId}", {
      params: {
        path: { gameId },
        header: { "X-CSRF-Token": csrfToken, "If-Match": entityTag },
      },
    })
    .catch(() => {
      throw new RatingCommandError("ambiguous", null, null);
    });
  if (data !== undefined) return data.ratingStatistics;
  throw failure(response.status, error, response);
}
