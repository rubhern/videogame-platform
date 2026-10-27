import { useCallback, useSyncExternalStore } from "react";

/**
 * Whether the viewport matches a media query, kept current as it changes. Read during render, so
 * the first paint already uses the matching layout; where the browser API is unavailable (a test
 * DOM) it never matches.
 */
export function useMediaQuery(query: string): boolean {
  const subscribe = useCallback(
    (onChange: () => void) => {
      const media = window.matchMedia?.(query);
      media?.addEventListener("change", onChange);
      return () => media?.removeEventListener("change", onChange);
    },
    [query],
  );
  return useSyncExternalStore(subscribe, () => window.matchMedia?.(query).matches ?? false);
}
