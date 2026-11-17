import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";

import { renderApp } from "../test/render-app";

function mockFeedback(reply: (request: Request) => Response | Promise<Response>) {
  const send = vi.fn(reply);
  vi.stubGlobal("fetch", (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(input);
    if (new URL(request.url).pathname === "/api/v1/session") {
      return Promise.resolve(Response.json({ authenticated: false }));
    }
    if (new URL(request.url).pathname === "/api/v1/feedback") return Promise.resolve(send(request));
    throw new Error("Unexpected request");
  });
  return send;
}

afterEach(() => vi.unstubAllGlobals());

it("opens About and Contact from the footer and About action", async () => {
  const send = mockFeedback(() => new Response(null, { status: 204 }));
  const user = userEvent.setup();
  renderApp("/privacidad");
  await user.click(within(screen.getByRole("contentinfo")).getByRole("link", { name: "Sobre Gameómetro" }));
  expect(await screen.findByRole("heading", { level: 1, name: "Sobre Gameómetro" })).toBeVisible();
  await user.click(screen.getByRole("link", { name: "Ir a contacto y sugerencias" }));
  expect(await screen.findByRole("heading", { level: 1, name: "Contacto y sugerencias" })).toBeVisible();
  expect(within(screen.getByRole("form")).getByRole("link", { name: "Política de privacidad" })).toHaveAttribute("href", "/privacidad");
  expect(send).not.toHaveBeenCalled();
});

it("validates required and optional fields and focuses the first error", async () => {
  const send = mockFeedback(() => new Response(null, { status: 204 }));
  const user = userEvent.setup();
  renderApp("/contacto");
  const submit = screen.getByRole("button", { name: "Enviar mensaje" });
  const category = screen.getByLabelText("Motivo");
  const message = screen.getByLabelText("Mensaje");
  const email = screen.getByLabelText(/Correo electrónico/);

  await user.click(submit);
  expect(await screen.findByRole("alert")).toHaveTextContent("Elige un motivo");
  await waitFor(() => expect(category).toHaveFocus());
  await user.selectOptions(category, "bug");
  expect(screen.getByText(/qué esperabas que pasara/)).toBeVisible();
  await user.type(message, "   ");
  await user.click(submit);
  expect(await screen.findByRole("alert")).toHaveTextContent("Escribe un mensaje");
  await waitFor(() => expect(message).toHaveFocus());
  await user.type(message, "Error al buscar");
  await user.type(email, "broken-address");
  await user.click(submit);
  expect(await screen.findByRole("alert")).toHaveTextContent("dirección de correo válida");
  await waitFor(() => expect(email).toHaveFocus());
  expect(send).not.toHaveBeenCalled();
});

it("waits for acceptance, suppresses duplicates while pending and sends without cookies", async () => {
  let resolve: (response: Response) => void = () => { throw new Error("No pending feedback"); };
  const promise = new Promise<Response>(finish => { resolve = finish; });
  const send = mockFeedback(() => promise);
  const user = userEvent.setup();
  renderApp("/contacto?motivo=datos");

  const category = screen.getByLabelText("Motivo");
  expect(category).toHaveValue("incorrect_game_information");
  const message = screen.getByLabelText("Mensaje");
  await user.type(message, "Falta la fecha.");
  const submit = screen.getByRole("button", { name: "Enviar mensaje" });
  await user.dblClick(submit);
  expect(await screen.findByText("Enviando tu mensaje…")).toBeVisible();
  expect(screen.queryByText(/ha aceptado tu mensaje/)).not.toBeInTheDocument();
  expect(send).toHaveBeenCalledTimes(1);

  const request = send.mock.calls[0]?.[0];
  if (!request) throw new Error("Feedback was not sent");
  expect(request.credentials).toBe("omit");
  expect(await request.json()).toEqual({ category: "incorrect_game_information", message: "Falta la fecha." });
  resolve(new Response(null, { status: 204 }));
  expect(await screen.findByText(/ha aceptado tu mensaje/)).toBeVisible();
  expect(message).toHaveValue("");
  expect(category).toHaveValue("");
});

it("keeps the draft on failure and retries only on request", async () => {
  let attempts = 0;
  const send = mockFeedback(() => ++attempts === 1
    ? Response.json({ code: "FEEDBACK_UNAVAILABLE" }, { status: 503 })
    : new Response(null, { status: 204 }));
  const user = userEvent.setup();
  renderApp("/contacto");
  await user.selectOptions(screen.getByLabelText("Motivo"), "other");
  const message = screen.getByLabelText("Mensaje");
  const email = screen.getByLabelText(/Correo electrónico/);
  await user.type(message, "Hola, me gusta la web.");
  await user.type(email, "player@example.invalid");

  const submit = screen.getByRole("button", { name: "Enviar mensaje" });
  await user.click(submit);
  expect(await screen.findByText(/podría llegar dos veces/)).toBeVisible();
  expect(message).toHaveValue("Hola, me gusta la web.");
  expect(email).toHaveValue("player@example.invalid");
  expect(send).toHaveBeenCalledTimes(1);
  await user.click(submit);
  expect(await screen.findByText(/ha aceptado tu mensaje/)).toBeVisible();
  expect(send).toHaveBeenCalledTimes(2);
});

it("honours a throttled response without discarding the draft", async () => {
  const send = mockFeedback(() => Response.json(
    { code: "RATE_LIMITED" },
    { status: 429, headers: { "Retry-After": "10" } },
  ));
  const user = userEvent.setup();
  renderApp("/contacto");
  await user.selectOptions(screen.getByLabelText("Motivo"), "suggestion");
  await user.type(screen.getByLabelText("Mensaje"), "Más cooperativos");
  const submit = screen.getByRole("button", { name: "Enviar mensaje" });
  await user.click(submit);
  expect(await screen.findByText(/demasiadas solicitudes/)).toBeVisible();
  expect(submit).toHaveAttribute("aria-disabled", "true");
  expect(screen.getByLabelText("Mensaje")).toHaveValue("Más cooperativos");
  expect(send).toHaveBeenCalledTimes(1);
});
