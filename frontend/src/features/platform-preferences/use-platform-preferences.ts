import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";

import type { SessionState } from "../session/session-api";
import { sessionQueryOptions, useSession } from "../session/use-session";
import type { Interests } from "./interests";
import {
  getMyPlatformPreferences,
  listPlatforms,
  putMyPlatformPreferences,
  type PlatformPreferences,
  type PlatformPreferencesError,
} from "./platform-preferences-api";

/**
 * Personal server state lives under `me`, which logout removes, so another account on the same
 * browser never sees it. Preferences change only through this browser's own command, which
 * replaces the cached value, or on another device, which a later read picks up.
 */
export const PLATFORM_PREFERENCES_QUERY_KEY = ["me", "platform-preferences"] as const;
const PREFERENCES_STALE_MS = 5 * 60_000;

export function platformPreferencesQueryOptions() {
  return {
    queryKey: PLATFORM_PREFERENCES_QUERY_KEY,
    queryFn: ({ signal }: { signal: AbortSignal }) => getMyPlatformPreferences(signal),
    staleTime: PREFERENCES_STALE_MS,
    retry: false,
  };
}

export function platformListQueryOptions() {
  return {
    queryKey: ["platforms"] as const,
    queryFn: ({ signal }: { signal: AbortSignal }) => listPlatforms(signal),
    staleTime: PREFERENCES_STALE_MS,
  };
}

/**
 * The session user's preferences, read only for an authenticated session. Discovery screens mount
 * on every navigation; they read the shell's session rather than asking for it again.
 */
export function useMyPlatformPreferences() {
  const session = useSession({ refetchOnMount: false });
  return useQuery<PlatformPreferences, PlatformPreferencesError>({
    ...platformPreferencesQueryOptions(),
    enabled: session.data?.authenticated === true,
  });
}

export function usePlatformList() {
  return useQuery(platformListQueryOptions());
}

function toInterests(
  session: SessionState | undefined,
  sessionPending: boolean,
  preferences: PlatformPreferences | undefined,
  preferencesPending: boolean,
): Interests {
  if (sessionPending) return { status: "pending" };
  if (session?.authenticated !== true) return { status: "unavailable" };
  if (preferencesPending) return { status: "pending" };
  if (preferences === undefined) return { status: "unavailable" };
  return preferences.platforms.length === 0
    ? { status: "none" }
    : { status: "ready", platforms: preferences.platforms };
}

/**
 * The visitor's preferences as release discovery applies them. A failed session or preference
 * read degrades to the general experience instead of blocking discovery.
 */
export function useInterests(): Interests {
  const session = useSession({ refetchOnMount: false });
  const preferences = useMyPlatformPreferences();
  return toInterests(
    session.data,
    session.isPending,
    preferences.data,
    session.data?.authenticated === true && preferences.isPending,
  );
}

/** The same resolution for a route loader, so the first discovery read is already personal. */
export async function loadInterests(queryClient: QueryClient): Promise<Interests> {
  const session = await queryClient.ensureQueryData(sessionQueryOptions()).catch(() => undefined);
  if (session?.authenticated !== true) return toInterests(session, false, undefined, false);
  const preferences = await queryClient
    .ensureQueryData(platformPreferencesQueryOptions())
    .catch(() => undefined);
  return toInterests(session, false, preferences, false);
}

/** Replaces the session user's set and makes every discovery screen apply it at once. */
export function useSavePlatformPreferences() {
  const queryClient = useQueryClient();
  return useMutation<
    PlatformPreferences,
    PlatformPreferencesError,
    { platformIds: readonly string[]; csrfToken: string }
  >({
    mutationFn: ({ platformIds, csrfToken }) => putMyPlatformPreferences(platformIds, csrfToken),
    onSuccess: (saved) => {
      queryClient.setQueryData(PLATFORM_PREFERENCES_QUERY_KEY, saved);
    },
  });
}
