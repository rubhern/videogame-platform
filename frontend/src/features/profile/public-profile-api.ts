import type { components } from "../../shared/api/generated/schema";
import { productApiClient } from "../../shared/api/product-api-client";
import { ProfileError } from "./profile-api";

export type PublicProfileSettings = components["schemas"]["PublicProfileSettings"];
export type PublicProfileChange = components["schemas"]["PublicProfileSettingsCommand"];
export type PublicProfileRatingSort = components["schemas"]["PublicProfileRatingSort"];
export type PublicProfileSection = components["schemas"]["PublicProfileSection"];

export const PROFILE_SETTINGS_KEY = ["me", "public-profile"] as const;
export const PUBLIC_PROFILES_KEY = ["public-profiles"] as const;
export const ALIAS_PATTERN = "[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*";
const aliasPattern = new RegExp(`^(?:${ALIAS_PATTERN})$`, "u");

export function isPlayerAlias(value: string) {
  return value.length >= 3 && value.length <= 24 && aliasPattern.test(value);
}

export function publicProfilePath(alias: string) {
  return `/u/${encodeURIComponent(alias)}`;
}

export async function getMyPublicProfileSettings(signal: AbortSignal) {
  const { data, error, response } = await productApiClient.GET("/me/public-profile", {
    signal,
    cache: "no-store",
  });
  if (!data) throw new ProfileError(response.status, error?.code);
  return data;
}

export async function updateMyPublicProfileSettings(body: PublicProfileChange, csrfToken: string) {
  const { data, error, response } = await productApiClient.PUT("/me/public-profile", {
    params: { header: { "X-CSRF-Token": csrfToken } },
    body,
    cache: "no-store",
  });
  if (!data) throw new ProfileError(response.status, error?.code);
  return data;
}

export async function getPublicProfile(alias: string, signal: AbortSignal) {
  const { data, error, response } = await productApiClient.GET("/profiles/{alias}", {
    params: { path: { alias } },
    signal,
    cache: "no-store",
  });
  if (!data) throw new ProfileError(response.status, error?.code);
  return data;
}

export async function getPublicProfileSection(
  alias: string, section: PublicProfileSection, page: number, signal: AbortSignal, sort?: PublicProfileRatingSort,
  pageSize = 12,
) {
  const { data, error, response } = await productApiClient.GET("/profiles/{alias}/sections/{section}", {
    params: { path: { alias, section }, query: { page, pageSize, ...(sort ? { sort } : {}) } },
    signal,
    cache: "no-store",
  });
  if (!data) throw new ProfileError(response.status, error?.code);
  return data;
}

export async function getPublicProfileStatistics(alias: string, signal: AbortSignal) {
  const { data, error, response } = await productApiClient.GET("/profiles/{alias}/statistics", {
    params: { path: { alias } },
    signal,
    cache: "no-store",
  });
  if (!data) throw new ProfileError(response.status, error?.code);
  return data;
}
