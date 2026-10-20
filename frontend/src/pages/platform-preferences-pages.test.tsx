import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { components } from "../shared/api/generated/schema";
import { renderApp } from "../test/render-app";

type PlatformPreferences = components["schemas"]["PlatformPreferences"];

const PS5 = { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" };
const SWITCH_2 = { platformId: "10000000-0000-4000-8000-000000000002", name: "Nintendo Switch 2" };
const PC = { platformId: "10000000-0000-4000-8000-000000000003", name: "Windows PC" };

type Script = {
  authenticated?: boolean;
  preferences?: PlatformPreferences;
  /** The status a preference command answers with; 200 echoes the saved set. */
  commandStatus?: number;
};

/** A scripted BFF holding one user's preferred platforms behind the session. */
function stubBff({ authenticated = true, preferences = { configured: false, platforms: [] }, commandStatus = 200 }: Script = {}) {
  let stored = preferences;
  const bodies: unknown[] = [];
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const request = input as Request;
    const url = new URL(request.url);
    if (url.pathname === "/api/v1/session") {
      return Response.json(authenticated ? { authenticated: true, csrfToken: "csrf-test" } : { authenticated: false });
    }
    if (url.pathname === "/api/v1/platforms") return Response.json({ items: [SWITCH_2, PS5, PC] });
    if (url.pathname === "/api/v1/me/platform-preferences" && request.method === "GET") {
      return authenticated ? Response.json(stored) : Response.json({ code: "AUTHENTICATION_REQUIRED" }, { status: 401 });
    }
    if (url.pathname === "/api/v1/me/platform-preferences" && request.method === "PUT") {
      bodies.push(await request.clone().json());
      if (commandStatus !== 200) {
        return Response.json({ code: "INTERNAL_ERROR", correlationId: "corr" }, { status: commandStatus });
      }
      const body = (await request.json()) as { platformIds: string[] };
      stored = {
        configured: true,
        platforms: [SWITCH_2, PS5, PC].filter((platform) => body.platformIds.includes(platform.platformId)),
      };
      return Response.json(stored);
    }
    if (url.pathname === "/api/v1/featured-releases" || url.pathname === "/api/v1/releases") {
      return Response.json({ code: "CATALOGUE_NOT_READY" }, { status: 503 });
    }
    return new Response(null, { status: 404 });
  });
  vi.stubGlobal("fetch", fetchMock);
  return Object.assign(fetchMock, { bodies });
}

function commands(fetchMock: ReturnType<typeof stubBff>) {
  return fetchMock.mock.calls.map((call) => call[0] as Request).filter((request) => request.method === "PUT");
}

afterEach(() => vi.unstubAllGlobals());

describe("first-run welcome", () => {
  it("saves several platforms with CSRF proof and continues to the destination", async () => {
    const user = userEvent.setup();
    const fetchMock = stubBff();
    const { router } = renderApp("/bienvenida?returnTo=%2Flanzamientos%3Fsort%3Dhype");

    expect(await screen.findByRole("heading", { level: 1, name: "¿A qué juegas?" })).toBeInTheDocument();
    await user.click(await screen.findByRole("checkbox", { name: "PlayStation 5" }));
    const save = screen.getByRole("button", { name: "Guardar y continuar" });
    await user.click(screen.getByRole("checkbox", { name: "Nintendo Switch 2" }));
    expect(screen.getByText("2 plataformas elegidas")).toBeInTheDocument();
    await user.click(save);

    await waitFor(() => expect(router.state.location.pathname).toBe("/lanzamientos"));
    expect(router.state.location.search).toBe("?sort=hype");
    const [command] = commands(fetchMock);
    expect(command?.headers.get("X-CSRF-Token")).toBe("csrf-test");
    expect(fetchMock.bodies).toEqual([{ platformIds: [PS5.platformId, SWITCH_2.platformId] }]);
  });

  it("records a skip as an empty choice so it is never offered again", async () => {
    const user = userEvent.setup();
    const fetchMock = stubBff();
    const { router } = renderApp("/bienvenida");

    await user.click(await screen.findByRole("button", { name: "Ahora no" }));

    await waitFor(() => expect(router.state.location.pathname).toBe("/"));
    expect(fetchMock.bodies).toEqual([{ platformIds: [] }]);
  });

  it("never blocks discovery when a skip cannot be saved", async () => {
    const user = userEvent.setup();
    stubBff({ commandStatus: 500 });
    const { router } = renderApp("/bienvenida?returnTo=%2Flanzamientos");

    await user.click(await screen.findByRole("button", { name: "Ahora no" }));

    await waitFor(() => expect(router.state.location.pathname).toBe("/lanzamientos"));
  });

  it("keeps the selection and explains a save that failed", async () => {
    const user = userEvent.setup();
    stubBff({ commandStatus: 500 });
    renderApp("/bienvenida");

    await user.click(await screen.findByRole("checkbox", { name: "Windows PC" }));
    await user.click(screen.getByRole("button", { name: "Guardar y continuar" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("No se pudieron guardar tus plataformas");
    expect(screen.getByRole("checkbox", { name: "Windows PC" })).toBeChecked();
  });

  it.each<[string, Script]>([
    ["an anonymous visitor", { authenticated: false }],
    ["a visitor who already chose", { preferences: { configured: true, platforms: [] } }],
  ])("is not shown to %s", async (_, script) => {
    const fetchMock = stubBff(script);
    const { router } = renderApp("/bienvenida?returnTo=%2Flanzamientos");

    await waitFor(() => expect(router.state.location.pathname).toBe("/lanzamientos"));
    expect(commands(fetchMock)).toHaveLength(0);
  });

  it("never follows a destination outside discovery", async () => {
    stubBff({ preferences: { configured: true, platforms: [] } });
    const { router } = renderApp("/bienvenida?returnTo=%2F%2Fevil.example");

    await waitFor(() => expect(router.state.location.pathname).toBe("/"));
  });
});

describe("Mis plataformas", () => {
  it("shows the saved choice and replaces it only on save", async () => {
    const user = userEvent.setup();
    const fetchMock = stubBff({ preferences: { configured: true, platforms: [PS5] } });
    renderApp("/mis-plataformas");

    expect(await screen.findByRole("checkbox", { name: "PlayStation 5" })).toBeChecked();
    const save = screen.getByRole("button", { name: "Guardar cambios" });
    expect(save).toBeDisabled();

    await user.click(screen.getByRole("checkbox", { name: "PlayStation 5" }));
    await user.click(screen.getByRole("checkbox", { name: "Windows PC" }));
    expect(screen.getByText("1 plataforma elegida · Sin guardar")).toBeInTheDocument();
    expect(commands(fetchMock)).toHaveLength(0);
    await user.click(save);

    expect(await screen.findByText("Selección guardada")).toBeInTheDocument();
    expect(fetchMock.bodies).toEqual([{ platformIds: [PC.platformId] }]);
    expect(screen.getByRole("checkbox", { name: "Windows PC" })).toBeChecked();
    expect(save).toBeDisabled();
    // One way out at the foot: back to the landing page.
    expect(screen.getByRole("link", { name: "Volver a Gameómetro" })).toHaveAttribute("href", "/");
    expect(screen.queryByRole("link", { name: /Explorar lanzamientos/ })).not.toBeInTheDocument();
  });

  it("clears every platform with one saved empty set", async () => {
    const user = userEvent.setup();
    const fetchMock = stubBff({ preferences: { configured: true, platforms: [PS5, SWITCH_2] } });
    renderApp("/mis-plataformas");

    await user.click(await screen.findByRole("button", { name: "Quitar todas" }));
    await user.click(screen.getByRole("button", { name: "Guardar cambios" }));

    expect(await screen.findByText("Selección guardada")).toBeInTheDocument();
    expect(fetchMock.bodies).toEqual([{ platformIds: [] }]);
  });

  it("keeps the previous choice when a save fails", async () => {
    const user = userEvent.setup();
    stubBff({ preferences: { configured: true, platforms: [PS5] }, commandStatus: 500 });
    renderApp("/mis-plataformas");

    await user.click(await screen.findByRole("checkbox", { name: "Windows PC" }));
    await user.click(screen.getByRole("button", { name: "Guardar cambios" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Tu elección anterior se mantiene");
  });

  it("asks an anonymous visitor to sign in and back", async () => {
    const fetchMock = stubBff({ authenticated: false });
    renderApp("/mis-plataformas");

    const main = await screen.findByRole("main");
    expect(await within(main).findByRole("link", { name: "Iniciar sesión" })).toHaveAttribute(
      "href",
      "/auth/start?returnTo=%2Fmis-plataformas",
    );
    expect(
      fetchMock.mock.calls.some((call) => new URL((call[0] as Request).url).pathname.startsWith("/api/v1/me")),
    ).toBe(false);
  });
});
