import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { AccountDeletionPage } from "./my-profile-page";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { renderApp } from "../test/render-app";

function bff({ authenticated = true, profile = {}, deletion = 202, deletionState = "pending" } = {}) {
  const commands: Request[] = [];
  vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockImplementation(async input => {
    const request = input as Request;
    const path = new URL(request.url).pathname;
    if (path.endsWith("/session")) return Response.json(authenticated ? { authenticated: true, csrfToken: "proof" } : { authenticated: false });
    if (path.endsWith("/me/profile")) return Response.json({ deletionAvailable: true, ...profile });
    if (path.endsWith("/me/account-deletion")) {
      commands.push(request.clone());
      if (deletion === 202) authenticated = false;
      return Response.json(deletion === 202 ? { state: "pending" } : { code: "RECENT_AUTHENTICATION_REQUIRED" }, { status: deletion });
    }
    if (path.endsWith("/account-deletion")) return Response.json({ state: deletionState });
    return Response.json({ code: "CATALOGUE_NOT_READY" }, { status: 503 });
  }));
  return commands;
}
afterEach(() => vi.unstubAllGlobals());
describe("Mi perfil", () => {
  it("redirects old deletion status bookmarks home without polling", async () => {
    bff({ authenticated: false, deletionState: "complete" });
    // This seam proves verified navigation independently of the home page's public reads.
    const router = createMemoryRouter([
      { path: "/mi-perfil/borrado", element: <AccountDeletionPage /> },
      { path: "/", element: <p>Inicio anónimo</p> },
    ], { initialEntries: ["/mi-perfil/borrado"] });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
    expect(await screen.findByText("Inicio anónimo")).toBeVisible();
    expect(router.state.location.state).toBeNull();
  });
  it("shows honest missing fields and existing personal and Keycloak destinations", async () => {
    bff(); renderApp("/mi-perfil");
    expect(await screen.findByRole("heading", { name: "Tu cuenta" })).toBeVisible();
    expect(screen.getAllByText("No disponible")).toHaveLength(1);
    expect(screen.queryByText("Nombre")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Cambiar contraseña" })).toHaveAttribute("href", "/auth/profile/security");
    expect(screen.getByRole("link", { name: "Mis puntuaciones" })).toHaveAttribute("href", "/mis-puntuaciones");
  });
  it("hides every personal field without an authenticated session", async () => {
    bff({ authenticated: false, profile: { email: "private@example.test" } }); renderApp("/mi-perfil");
    expect(await screen.findByRole("heading", { name: "Necesitas una sesión activa" })).toBeVisible();
    expect(screen.queryByText("private@example.test")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Exportar mis datos" })).not.toBeInTheDocument();
  });
  it("focuses cancellation and Escape leaves the account untouched", async () => {
    const calls = bff(); const user = userEvent.setup(); renderApp("/mi-perfil");
    const trigger = await screen.findByRole("button", { name: "Eliminar mi cuenta" });
    await user.click(trigger);
    expect(screen.getByRole("button", { name: "Conservar mi cuenta" })).toHaveFocus();
    expect(screen.getByRole("button", { name: "Sí, solicitar el borrado" })).toBeDisabled();
    await user.keyboard("{Escape}"); expect(screen.getByRole("button", { name: "Eliminar mi cuenta" })).toHaveFocus(); expect(calls).toHaveLength(0);
  });
  it("requires typed confirmation and submits only the fixed confirmation with CSRF", async () => {
    const calls = bff(); const user = userEvent.setup(); const { router } = renderApp("/mi-perfil");
    await user.click(await screen.findByRole("button", { name: "Eliminar mi cuenta" }));
    await user.type(screen.getByLabelText("Escribe ELIMINAR para confirmar"), "ELIMINAR");
    await user.click(screen.getByRole("button", { name: "Sí, solicitar el borrado" }));
    await waitFor(() => expect(router.state.location.pathname).toBe("/"));
    expect(screen.queryByText("Comprobando el resultado…")).not.toBeInTheDocument();
    expect(screen.queryByText("Solicitud recibida")).not.toBeInTheDocument();
    expect(await screen.findByRole("link", { name: "Iniciar sesión" })).toBeVisible();
    expect(calls).toHaveLength(1); expect(calls[0]?.headers.get("X-CSRF-Token")).toBe("proof");
    expect(await calls[0]?.json()).toEqual({ confirmation: "ELIMINAR" });
    expect(screen.queryByText("Tu cuenta se ha eliminado")).not.toBeInTheDocument();
  });
  it("requires renewed authentication after a stale auth_time without claiming deletion", async () => {
    bff({ deletion: 403 }); const user = userEvent.setup(); renderApp("/mi-perfil");
    await user.click(await screen.findByRole("button", { name: "Eliminar mi cuenta" }));
    await user.type(screen.getByLabelText("Escribe ELIMINAR para confirmar"), "ELIMINAR");
    await user.click(screen.getByRole("button", { name: "Sí, solicitar el borrado" }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveFocus());
    expect(screen.getByRole("alert")).toHaveTextContent("Vuelve a autenticarte");
    expect(screen.getByRole("link", { name: "Volver a autenticarme" })).toHaveAttribute("href", "/auth/profile/reauthenticate");
  });
  it("keeps the deletion button visible and explains unavailability without admitting a request", async () => {
    bff({ profile: { deletionAvailable: false } }); renderApp("/mi-perfil");
    expect(await screen.findByText(/El borrado automático no está disponible ahora/)).toBeVisible();
    const trigger = screen.getByRole("button", { name: "Eliminar mi cuenta" });
    expect(trigger).toBeVisible();
    expect(trigger).toBeDisabled();
    expect(trigger).toHaveAccessibleDescription(/El borrado automático no está disponible ahora/);
  });
});
