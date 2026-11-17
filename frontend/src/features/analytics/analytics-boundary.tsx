import { useEffect, useRef } from "react";
import { useLocation } from "react-router-dom";

import { useSession } from "../session/use-session";
import { ConsentBanner } from "./consent-banner";
import { consentStore, useConsent, type ConsentStore } from "./consent";
import { positionBucket, setEventSink, trackEvent } from "./events";
import {
  disableGoogleAnalytics,
  enableGoogleAnalytics,
  sendEvent,
  sendPageView,
  type PageContext,
} from "./google-analytics";
import { reportedRoute, type ReportedRoute } from "./route-names";
import {
  markSignInStart,
  setSignInTracking,
  signInIntentOf,
  takePendingSignIn,
} from "./sign-in-intent";
import { urlSignals } from "./url-signals";
import { useAnalyticsMeasurementId } from "./use-analytics-measurement-id";

/** An external referrer is reduced to its origin; an internal one to the previous template. */
function externalReferrer(): string {
  try {
    const referrer = document.referrer === "" ? null : new URL(document.referrer);
    if (referrer === null || referrer.origin === window.location.origin) return "";
    return `${referrer.origin}/`;
  } catch {
    return "";
  }
}

function pageContext(route: ReportedRoute, previous: ReportedRoute | null): PageContext {
  const origin = window.location.origin;
  return {
    location: `${origin}${route.path}`,
    referrer: previous === null ? externalReferrer() : `${origin}${previous.path}`,
    title: route.name,
  };
}

const LISTS = new Set(["featured_hero", "featured"]);

/**
 * Consent-gated product analytics for the whole shell (#507). Without a configured measurement ID
 * it renders nothing and does nothing. With one, it asks once, and only after acceptance loads
 * GA4, reports route templates and installs the product event sink.
 */
export function AnalyticsBoundary({ store = consentStore }: { store?: ConsentStore }) {
  const measurementId = useAnalyticsMeasurementId();
  const consent = useConsent(store);
  const location = useLocation();
  const session = useSession({ refetchOnMount: false });
  const active = measurementId !== null && consent.state === "granted";

  const current = reportedRoute(location.pathname);
  const currentPath = current?.path ?? null;
  // The page events belong to, read by the event sink outside rendering. Declared first, so the
  // effects below always see the current page.
  const currentRef = useRef(current);
  useEffect(() => {
    currentRef.current = reportedRoute(location.pathname);
  }, [location.pathname]);
  // The last reported page, so a page view names where the visitor came from.
  const reported = useRef<{ route: ReportedRoute; search: string } | null>(null);

  // Start or stop measurement as configuration and consent allow.
  useEffect(() => {
    if (measurementId === null) return;
    if (!active) {
      setEventSink(null);
      setSignInTracking(false);
      if (consent.state === "denied") disableGoogleAnalytics(measurementId);
      return;
    }
    const route = currentRef.current;
    enableGoogleAnalytics(
      measurementId,
      route === null
        ? { location: `${window.location.origin}/`, referrer: "", title: "home" }
        : pageContext(route, null),
    );
    setEventSink((name, params) => {
      const page = currentRef.current;
      if (page === null) return;
      sendEvent(name, { ...params, route: page.name });
    });
    setSignInTracking(true);
    return () => {
      setEventSink(null);
    };
  }, [active, consent.state, measurementId]);

  // One page view per route; URL-state changes inside a route become product signals instead.
  useEffect(() => {
    const current = currentRef.current;
    if (!active || current === null) return;
    const previous = reported.current;
    if (previous !== null && previous.route.path === current.path) {
      if (previous.search !== location.search) {
        for (const signal of urlSignals(current.name, previous.search, location.search, new Date())) {
          sendEvent(signal.name, { ...signal.params, route: current.name });
        }
        reported.current = { route: current, search: location.search };
      }
      return;
    }
    sendPageView(pageContext(current, previous?.route ?? null), {
      previous_route: previous?.route.name ?? "external",
    });
    reported.current = { route: current, search: location.search };
  }, [active, currentPath, location.search]);

  // A sign-in started under consent is counted once when the visitor comes back signed in.
  const authenticated = session.data?.authenticated;
  useEffect(() => {
    if (!active || authenticated === undefined) return;
    const intent = takePendingSignIn();
    if (intent !== null && authenticated) trackEvent("sign_in_return", { intent });
  }, [active, authenticated]);

  // Links leaving for authentication, and openings from ranked lists, are read from the click.
  useEffect(() => {
    if (!active) return;
    function onClick(event: MouseEvent) {
      const target = event.target;
      if (!(target instanceof Element)) return;
      const link = target.closest<HTMLAnchorElement>("a[href]");
      if (link === null) return;
      const intent = signInIntentOf(link.href);
      if (intent !== null) {
        markSignInStart(intent);
        return;
      }
      const list = link.closest<HTMLElement>("[data-analytics-list]")?.dataset.analyticsList;
      const position = Number(
        link.closest<HTMLElement>("[data-analytics-position]")?.dataset.analyticsPosition,
      );
      if (list !== undefined && LISTS.has(list) && Number.isInteger(position) && position > 0) {
        trackEvent("list_item_open", {
          list: list as "featured_hero" | "featured",
          position: positionBucket(position),
        });
      }
    }
    document.addEventListener("click", onClick, true);
    return () => document.removeEventListener("click", onClick, true);
  }, [active]);

  if (measurementId === null) return null;
  return consent.state === "unset" || consent.reviewing ? (
    <ConsentBanner choice={consent.state} onChoose={consent.choose} reviewing={consent.reviewing} />
  ) : null;
}
