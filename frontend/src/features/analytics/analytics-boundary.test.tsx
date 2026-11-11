import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { renderApp } from "../../test/render-app";
import { CONSENT_STORAGE_KEY, consentStore } from "./consent";
import { trackEvent } from "./events";

const MEASUREMENT_ID = "G-TEST1234";

type AnalyticsWindow = Window & { dataLayer?: IArguments[] } & Record<string, unknown>;
const analyticsWindow = () => window as unknown as AnalyticsWindow;

function stubApi(measurementId: string | null) {
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const url = new URL(input instanceof Request ? input.url : String(input), "http://localhost");
    if (url.pathname.endsWith("/web-analytics")) {
      return Response.json(measurementId === null ? {} : { googleAnalytics: { measurementId } });
    }
    return Response.json({ authenticated: false });
  });
  vi.stubGlobal("fetch", fetchMock);
}

/** Every gtag command queued so far, as plain arrays. */
function commands(): unknown[][] {
  return (analyticsWindow().dataLayer ?? []).map((entry) => Array.from(entry));
}

function pageViews() {
  return commands().filter(([kind, name]) => kind === "event" && name === "page_view");
}

function locations() {
  return commands()
    .filter(([kind]) => kind === "set")
    .map(([, values]) => (values as { page_location: string }).page_location);
}

function googleScript() {
  return document.querySelector<HTMLScriptElement>('script[src*="googletagmanager.com"]');
}

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
  googleScript()?.remove();
  const target = analyticsWindow();
  delete target.dataLayer;
  delete target.gtag;
  Reflect.deleteProperty(target, `ga-disable-${MEASUREMENT_ID}`);
  consentStore.reload();
});

describe("consent-gated analytics", () => {
  it("asks nothing and loads nothing when the deployment measures nothing", async () => {
    stubApi(null);
    renderApp("/cookies");

    await screen.findByRole("heading", { level: 1 });
    await waitFor(() =>
      expect(
        vi.mocked(fetch).mock.calls.some(([input]) =>
          String(input instanceof Request ? input.url : input).endsWith("/web-analytics"),
        ),
      ).toBe(true),
    );
    expect(screen.queryByRole("region", { name: /ayudas a mejorar/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Configurar cookies" })).not.toBeInTheDocument();
    expect(googleScript()).toBeNull();
  });

  it("loads nothing before a choice and nothing after rejecting", async () => {
    stubApi(MEASUREMENT_ID);
    const user = userEvent.setup();
    renderApp("/cookies");

    const banner = await screen.findByRole("region", { name: /ayudas a mejorar/i });
    expect(googleScript()).toBeNull();
    expect(commands()).toEqual([]);
    // Both choices are plain buttons of the same style; nothing is preselected.
    const reject = within(banner).getByRole("button", { name: "Rechazar" });
    const accept = within(banner).getByRole("button", { name: "Aceptar" });
    expect(reject.className).toBe(accept.className);

    await user.click(reject);

    expect(screen.queryByRole("region", { name: /ayudas a mejorar/i })).not.toBeInTheDocument();
    expect(JSON.parse(window.localStorage.getItem(CONSENT_STORAGE_KEY) ?? "{}")).toMatchObject({ choice: "denied" });
    trackEvent("featured_period", { period: "month" });
    expect(googleScript()).toBeNull();
    expect(commands()).toEqual([]);
    expect(screen.getByRole("button", { name: "Configurar cookies" })).toBeInTheDocument();
  });

  it("after acceptance reports route templates only, then stops on withdrawal", async () => {
    stubApi(MEASUREMENT_ID);
    const user = userEvent.setup();
    const { router } = renderApp("/cookies?utm=secret#fragment");

    await user.click(
      within(await screen.findByRole("region", { name: /ayudas a mejorar/i })).getByRole("button", {
        name: "Aceptar",
      }),
    );

    expect(googleScript()?.src).toBe(`https://www.googletagmanager.com/gtag/js?id=${MEASUREMENT_ID}`);
    const config = commands().find(([kind]) => kind === "config");
    expect(config).toEqual([
      "config",
      MEASUREMENT_ID,
      expect.objectContaining({
        send_page_view: false,
        allow_google_signals: false,
        allow_ad_personalization_signals: false,
        page_location: `${window.location.origin}/cookies`,
      }),
    ]);
    expect(commands()).toContainEqual(["consent", "default", expect.objectContaining({ ad_storage: "denied", analytics_storage: "granted" })]);
    await waitFor(() => expect(pageViews()).toHaveLength(1));
    expect(pageViews()[0]?.[2]).toEqual({ previous_route: "external" });

    await act(() => router.navigate("/condiciones?q=alice%40example.com"));
    await waitFor(() => expect(pageViews()).toHaveLength(2));
    expect(pageViews()[1]?.[2]).toEqual({ previous_route: "legal" });
    expect(locations().at(-1)).toBe(`${window.location.origin}/condiciones`);
    expect(JSON.stringify(commands())).not.toMatch(/alice|utm|secret|fragment/);

    trackEvent("featured_period", { period: "month" });
    expect(commands().at(-1)).toEqual(["event", "featured_period", { period: "month", route: "legal" }]);

    await user.click(screen.getByRole("button", { name: "Configurar cookies" }));
    const review = screen.getByRole("region", { name: /ayudas a mejorar/i });
    expect(within(review).getByText("Ahora mismo: aceptadas.")).toBeInTheDocument();
    await user.click(within(review).getByRole("button", { name: "Rechazar" }));

    expect(analyticsWindow()[`ga-disable-${MEASUREMENT_ID}`]).toBe(true);
    const queued = commands().length;
    trackEvent("featured_period", { period: "week" });
    await act(() => router.navigate("/privacidad"));
    expect(commands()).toHaveLength(queued);
  });

  it("counts a sign-in started under consent once, on the signed-in return", async () => {
    stubApi(MEASUREMENT_ID);
    window.localStorage.setItem(
      CONSENT_STORAGE_KEY,
      JSON.stringify({ version: 1, choice: "granted", decidedAt: new Date().toISOString() }),
    );
    consentStore.reload();
    window.sessionStorage.setItem("gameometro_analytics_sign_in", "register");
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockImplementation(async (input) => {
        const url = String(input instanceof Request ? input.url : input);
        if (url.endsWith("/web-analytics")) return Response.json({ googleAnalytics: { measurementId: MEASUREMENT_ID } });
        return Response.json({ authenticated: true, csrfToken: "csrf" });
      }),
    );
    renderApp("/cookies");

    await waitFor(() =>
      expect(commands()).toContainEqual(["event", "sign_in_return", { intent: "register", route: "legal" }]),
    );
    expect(window.sessionStorage.getItem("gameometro_analytics_sign_in")).toBeNull();
  });
});
