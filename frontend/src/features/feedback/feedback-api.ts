import type { components } from "../../shared/api/generated/schema";
import { productApiClient } from "../../shared/api/product-api-client";

type FeedbackDraft = components["schemas"]["FeedbackSubmission"];
export type FeedbackCategory = FeedbackDraft["category"];

export class FeedbackError extends Error {
  constructor(
    readonly status: number,
    readonly retryAfter: number,
    readonly pointer?: string,
    readonly code?: string,
  ) {
    super("Feedback could not be confirmed");
  }
}

export async function submitFeedback(draft: FeedbackDraft): Promise<void> {
  const { response, error } = await productApiClient.POST("/feedback", {
    body: draft,
    credentials: "omit",
    redirect: "error",
  });

  if (response.status !== 204) {
    const retryAfter = Number(response.headers.get("Retry-After"));
    throw new FeedbackError(
      response.status,
      Number.isSafeInteger(retryAfter) && retryAfter > 0 ? Math.min(retryAfter, 172_800) : 0,
      error?.violations?.[0]?.pointer,
      error?.code,
    );
  }
}
