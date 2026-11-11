import { trackEvent, type ProductEvents } from "./events";

type Intent = ProductEvents["sign_in_start"]["intent"];

const PENDING_KEY = "gameometro_analytics_sign_in";
const INTENTS: readonly Intent[] = ["login", "register", "rating"];

let active = false;

/** Set by the analytics boundary: a pending sign-in is only remembered for a consenting visitor. */
export function setSignInTracking(enabled: boolean) {
  active = enabled;
  if (!enabled) clearPendingSignIn();
}

/**
 * Reports that the visitor is leaving for the identity provider and remembers, for this tab only,
 * which journey they started, so the return can be counted once. It stores a bounded label only.
 */
export function markSignInStart(intent: Intent) {
  if (!active) return;
  trackEvent("sign_in_start", { intent });
  try {
    window.sessionStorage.setItem(PENDING_KEY, intent);
  } catch {
    // Without storage the return simply goes uncounted.
  }
}

/** The pending journey, consumed once whatever the outcome. */
export function takePendingSignIn(): Intent | null {
  try {
    const value = window.sessionStorage.getItem(PENDING_KEY);
    window.sessionStorage.removeItem(PENDING_KEY);
    return INTENTS.find((intent) => intent === value) ?? null;
  } catch {
    return null;
  }
}

function clearPendingSignIn() {
  try {
    window.sessionStorage.removeItem(PENDING_KEY);
  } catch {
    // Nothing to clear.
  }
}

/** The journey a same-origin authentication link starts, or null for any other link. */
export function signInIntentOf(href: string): Intent | null {
  const url = new URL(href, window.location.origin);
  if (url.origin !== window.location.origin) return null;
  if (url.pathname === "/auth/start") {
    return url.searchParams.get("intent") === "register" ? "register" : "login";
  }
  return url.pathname.startsWith("/auth/rating-intent") ? "rating" : null;
}
