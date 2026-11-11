/**
 * The only code that talks to Google Analytics 4 (#507, ADR-0038). It runs exclusively after the
 * visitor accepts: before that no script, cookie or request exists (Consent Mode basic). Automatic
 * page views are off; the application reports sanitized route templates itself. Signals,
 * advertising features and user IDs are never enabled.
 */
type Gtag = (...args: unknown[]) => void;
type AnalyticsWindow = Window & { dataLayer?: unknown[]; gtag?: Gtag } & Record<string, unknown>;

const TAG_ORIGIN = "https://www.googletagmanager.com";
const SCRIPT_ID = "gameometro-ga4";
/** About thirteen months, as the cookie notice states; consent itself is asked again yearly. */
const COOKIE_LIFETIME_SECONDS = 395 * 24 * 60 * 60;

function analyticsWindow(): AnalyticsWindow {
  return window as unknown as AnalyticsWindow;
}

function gtag(...args: unknown[]) {
  const target = analyticsWindow();
  target.dataLayer ??= [];
  // gtag.js reads its queue as `arguments` objects, exactly as Google's snippet pushes them.
  target.gtag ??= function gtagQueue() {
    // eslint-disable-next-line prefer-rest-params
    target.dataLayer?.push(arguments);
  };
  target.gtag(...args);
}

export type PageContext = { location: string; referrer: string; title: string };

/** Starts measurement for an accepted visitor; repeated calls only re-enable it. */
export function enableGoogleAnalytics(measurementId: string, page: PageContext) {
  const target = analyticsWindow();
  target[`ga-disable-${measurementId}`] = false;
  if (document.getElementById(SCRIPT_ID) === null) {
    gtag("consent", "default", {
      ad_storage: "denied",
      ad_user_data: "denied",
      ad_personalization: "denied",
      analytics_storage: "granted",
    });
    gtag("js", new Date());
    gtag("config", measurementId, {
      send_page_view: false,
      allow_google_signals: false,
      allow_ad_personalization_signals: false,
      // Host-only, Secure and Lax like the rest of the cookie inventory: never shared with the
      // identity provider's host.
      cookie_domain: "none",
      cookie_flags: "SameSite=Lax;Secure",
      cookie_expires: COOKIE_LIFETIME_SECONDS,
      page_location: page.location,
      page_referrer: page.referrer,
      page_title: page.title,
    });
    const script = document.createElement("script");
    script.id = SCRIPT_ID;
    script.async = true;
    script.src = `${TAG_ORIGIN}/gtag/js?id=${encodeURIComponent(measurementId)}`;
    document.head.appendChild(script);
  }
}

/** Stops measurement at once and removes the first-party `_ga*` cookies this site can reach. */
export function disableGoogleAnalytics(measurementId: string) {
  analyticsWindow()[`ga-disable-${measurementId}`] = true;
  const host = window.location.hostname;
  const domains = ["", host, `.${host}`, `.${host.split(".").slice(-2).join(".")}`];
  for (const cookie of document.cookie.split(";")) {
    const name = cookie.split("=")[0]?.trim() ?? "";
    if (!/^_ga(?:_[A-Z0-9]+)?$/.test(name)) continue;
    for (const domain of domains) {
      document.cookie = `${name}=; Max-Age=0; Path=/${domain === "" ? "" : `; Domain=${domain}`}`;
    }
  }
}

/** One page view with the sanitized location; later events inherit the same location. */
export function sendPageView(page: PageContext, params: Readonly<Record<string, string>>) {
  gtag("set", {
    page_location: page.location,
    page_referrer: page.referrer,
    page_title: page.title,
  });
  gtag("event", "page_view", { ...params });
}

export function sendEvent(name: string, params: Readonly<Record<string, string>>) {
  gtag("event", name, { ...params });
}
