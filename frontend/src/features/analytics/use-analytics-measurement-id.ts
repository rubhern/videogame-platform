import { useQuery } from "@tanstack/react-query";

import { analyticsConfigurationQueryOptions } from "./analytics-api";

/** The deployment's measurement ID, or null when it measures nothing or its read failed. */
export function useAnalyticsMeasurementId(): string | null {
  return useQuery(analyticsConfigurationQueryOptions()).data?.measurementId ?? null;
}
