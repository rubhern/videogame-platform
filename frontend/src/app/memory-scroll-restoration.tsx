import { useContext, useEffect, useLayoutEffect } from "react";
import {
  UNSAFE_DataRouterContext as DataRouterContext,
  UNSAFE_DataRouterStateContext as DataRouterStateContext,
  useLocation,
} from "react-router-dom";

// One document's offsets, keyed by history entry. They die with the document: the
// approved privacy baseline keeps localStorage, sessionStorage and IndexedDB empty.
const savedScrollPositions: Record<string, number> = {};

/** React Router's ScrollRestoration without its sessionStorage persistence on pagehide. */
export function MemoryScrollRestoration() {
  const router = useContext(DataRouterContext)?.router;
  const state = useContext(DataRouterStateContext);
  const location = useLocation();
  const restoreScrollPosition = state?.restoreScrollPosition;
  const preventScrollReset = state?.preventScrollReset;

  useEffect(() => {
    window.history.scrollRestoration = "manual";
    // Let the browser keep its own entry state for bfcache and history traversal.
    const release = () => { window.history.scrollRestoration = "auto"; };
    window.addEventListener("pagehide", release);
    return () => {
      window.removeEventListener("pagehide", release);
      release();
    };
  }, []);

  useLayoutEffect(
    () => router?.enableScrollRestoration(savedScrollPositions, () => window.scrollY),
    [router],
  );

  useLayoutEffect(() => {
    if (restoreScrollPosition === false) return;
    if (typeof restoreScrollPosition === "number") {
      window.scrollTo(0, restoreScrollPosition);
      return;
    }
    if (location.hash) {
      try {
        const target = document.getElementById(decodeURIComponent(location.hash.slice(1)));
        if (target) {
          target.scrollIntoView();
          return;
        }
      } catch {
        // An undecodable fragment names no element; fall through to the reset.
      }
    }
    if (preventScrollReset === true) return;
    window.scrollTo(0, 0);
  }, [location, restoreScrollPosition, preventScrollReset]);

  return null;
}
