import { readFile } from "node:fs/promises";
import { chromium, request } from "playwright";

import { classifyReleasesOutcome } from "./releases-outcome.mjs";

const applicationOrigin = requiredEnvironment("PRIVATE_DEV_APPLICATION_ORIGIN");
const keycloakOrigin = requiredEnvironment("PRIVATE_DEV_KEYCLOAK_ORIGIN");
const expectedVersion = requiredEnvironment("EXPECTED_APPLICATION_VERSION");
const expectedRevision = requiredEnvironment("EXPECTED_SOURCE_REVISION");
const correlationId = requiredEnvironment("SMOKE_CORRELATION_ID");
const traceId = requiredEnvironment("SMOKE_TRACE_ID");
const username = await readSecret("OIDC_SMOKE_USERNAME_FILE");
const password = await readSecret("OIDC_SMOKE_PASSWORD_FILE");
const completedChecks = [];
const igdbHosts = new Set(["api.igdb.com", "igdb.com", "images.igdb.com", "www.igdb.com"]);

function requiredEnvironment(name) {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Required smoke configuration is missing: ${name}`);
  }
  return value;
}

async function readSecret(environmentName) {
  const path = requiredEnvironment(environmentName);
  const value = (await readFile(path, "utf8")).replace(/[\r\n]+$/, "");
  if (!value) {
    throw new Error(`Required smoke credential is empty: ${environmentName}`);
  }
  return value;
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

async function jsonResponse(response, check) {
  assert(response.ok(), `${check} returned HTTP ${response.status()}`);
  return response.json();
}

async function releasesOutcome(response, check) {
  const status = response.status();
  let body;
  try {
    body = await response.json();
  } catch {
    throw new Error(`${check} returned a non-JSON HTTP ${status} response`);
  }

  return classifyReleasesOutcome(status, body, check);
}

async function sessionState(page) {
  return page.evaluate(async () => {
    const response = await fetch("/api/v1/session", {
      credentials: "same-origin",
      headers: { Accept: "application/json" },
    });
    if (!response.ok) {
      throw new Error(`Session request returned HTTP ${response.status}.`);
    }
    return response.json();
  });
}

let management;
let browser;

try {
  management = await request.newContext({ baseURL: "http://application:8081" });

  const liveness = await jsonResponse(
    await management.get("/actuator/health/liveness"),
    "liveness",
  );
  assert(liveness.status === "UP", "liveness did not report UP");
  completedChecks.push("liveness");

  const readiness = await jsonResponse(
    await management.get("/actuator/health/readiness"),
    "readiness",
  );
  assert(readiness.status === "UP", "readiness did not report UP");
  completedChecks.push("readiness");

  const info = await jsonResponse(await management.get("/actuator/info"), "version metadata");
  assert(info.build?.version === expectedVersion, "application version metadata did not match");
  assert(
    info.build?.sourceRevision === expectedRevision,
    "application source revision metadata did not match",
  );
  completedChecks.push("version-metadata");

  browser = await chromium.launch();
  const context = await browser.newContext({ baseURL: applicationOrigin });
  const page = await context.newPage();
  await page.route("**/*", async (route) => {
    if (igdbHosts.has(new URL(route.request().url()).hostname)) {
      await route.abort("blockedbyclient");
      return;
    }
    await route.continue();
  });
  const releasesResponse = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return url.pathname === "/api/v1/releases" && response.request().resourceType() === "fetch";
  });

  await page.goto("/", { waitUntil: "domcontentloaded" });
  const renderedReleases = await releasesResponse;
  const renderedReleasesOutcome = await releasesOutcome(renderedReleases, "releases API");
  await page.locator("h1").waitFor({ state: "visible" });
  const heading = (await page.locator("h1").textContent()) ?? "";
  assert(
    /Lanzamientos recientes|Próximos lanzamientos/.test(heading),
    "minimal browser shell heading is absent",
  );
  if (renderedReleasesOutcome.state === "not-ready") {
    await page
      .getByRole("heading", { name: "El catálogo todavía no está disponible" })
      .waitFor({ state: "visible" });
  } else if (renderedReleasesOutcome.empty) {
    await page
      .getByRole("heading", { name: "Sin lanzamientos para esta selección" })
      .waitFor({ state: "visible" });
  }
  completedChecks.push("releases-api", "browser-shell");

  const metrics = await jsonResponse(await management.get("/actuator/metrics"), "metrics");
  for (const name of ["http.server.requests", "jvm.memory.used", "jdbc.connections.active"]) {
    assert(metrics.names?.includes(name), `required diagnostic metric is absent: ${name}`);
  }
  completedChecks.push("diagnostic-metrics");

  const tracedRelease = await page.evaluate(
    async ({ expectedCorrelationId, expectedTraceId }) => {
      const response = await fetch("/api/v1/releases?page=1&pageSize=1", {
        headers: {
          Accept: "application/json",
          "X-Correlation-ID": expectedCorrelationId,
          traceparent: `00-${expectedTraceId}-0123456789abcdef-01`,
        },
      });
      let code = null;
      try {
        code = (await response.json()).code ?? null;
      } catch {
        // The assertion outside the browser reports an unexpected non-JSON response.
      }
      return {
        code,
        correlationId: response.headers.get("X-Correlation-ID"),
        status: response.status,
      };
    },
    { expectedCorrelationId: correlationId, expectedTraceId: traceId },
  );
  assert(
    tracedRelease.status === 200 ||
      (tracedRelease.status === 503 && tracedRelease.code === "CATALOGUE_NOT_READY"),
    "correlated releases API smoke returned an unexpected state",
  );
  assert(tracedRelease.correlationId === correlationId, "correlation response did not match");
  completedChecks.push("trace-correlation");

  // Keep the deployment subset aligned with the #34 compatibility proof in
  // frontend/tests/oidc-session.spec.ts without importing its full E2E harness.
  assert((await sessionState(page)).authenticated === false, "initial session was not anonymous");
  await page.goto("/auth/login/keycloak");
  assert(
    new URL(page.url()).origin === new URL(keycloakOrigin).origin,
    "OIDC authorization did not reach the configured Keycloak origin",
  );
  await page.locator("#username").fill(username);
  await page.locator("#password").fill(password);
  await page.locator("#kc-login").click();
  await page.waitForURL((url) => url.origin === new URL(applicationOrigin).origin);

  const authenticatedSession = await sessionState(page);
  assert(authenticatedSession.authenticated === true, "OIDC login did not establish a BFF session");
  assert(typeof authenticatedSession.csrfToken === "string", "authenticated session omitted CSRF material");

  const sessionCookie = (await context.cookies(applicationOrigin)).find(
    (cookie) => cookie.name === "__Host-vgp_session",
  );
  assert(sessionCookie, "opaque application session cookie is absent");
  assert(sessionCookie.httpOnly, "application session cookie is not HttpOnly");
  assert(sessionCookie.secure, "application session cookie is not Secure");
  assert(sessionCookie.sameSite === "Lax", "application session cookie is not SameSite=Lax");
  assert(sessionCookie.value.split(".").length !== 3, "application cookie resembles a browser JWT");

  const browserState = await page.evaluate(() => ({
    cookie: document.cookie,
    localStorage: { ...window.localStorage },
    sessionStorage: { ...window.sessionStorage },
    url: window.location.href,
  }));
  assert(!browserState.cookie.includes("__Host-vgp_session"), "session cookie is script-readable");
  assert(Object.keys(browserState.localStorage).length === 0, "localStorage is not empty");
  assert(Object.keys(browserState.sessionStorage).length === 0, "sessionStorage is not empty");
  assert(
    !/(?:access_token|refresh_token|id_token|code|state|session_state)=/i.test(browserState.url),
    "OIDC material remained in the application URL",
  );

  const logoutStatus = await page.evaluate(async (csrfToken) => {
    const response = await fetch("/api/v1/session", {
      method: "POST",
      credentials: "same-origin",
      headers: { "X-CSRF-Token": csrfToken },
    });
    return response.status;
  }, authenticatedSession.csrfToken);
  assert(logoutStatus === 204, "CSRF-protected logout did not return HTTP 204");
  assert((await sessionState(page)).authenticated === false, "logout did not clear the BFF session");
  completedChecks.push("oidc-bff-session");

  console.log(`Private-dev deployment smoke passed: ${completedChecks.join(", ")}.`);
} catch (error) {
  const message = error instanceof Error ? error.message : "unknown failure";
  console.error(`Private-dev deployment smoke failed: ${message}`);
  process.exitCode = 1;
} finally {
  await browser?.close();
  await management?.dispose();
}
