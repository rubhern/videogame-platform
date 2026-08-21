import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { renderApp } from "../test/render-app";

describe("technical foundation routing", () => {
  it("renders the technical placeholder with semantic structure", () => {
    renderApp();

    expect(
      screen.getByRole("heading", {
        level: 1,
        name: "El frontend ya puede crecer por slices verticales.",
      }),
    ).toBeInTheDocument();
    expect(screen.getByRole("main")).toBeInTheDocument();
    expect(screen.getByRole("list", { name: "Tecnologías disponibles" })).toBeInTheDocument();
    expect(screen.getByText("TanStack Query")).toBeInTheDocument();
  });

  it("offers a keyboard-accessible return from an unknown route", async () => {
    const user = userEvent.setup();
    renderApp("/not-implemented");

    expect(
      screen.getByRole("heading", { level: 1, name: "Página no encontrada" }),
    ).toBeInTheDocument();

    const homeLink = screen.getByRole("link", { name: "Volver al inicio" });
    homeLink.focus();
    await user.keyboard("{Enter}");

    await screen.findByRole("heading", {
      level: 1,
      name: "El frontend ya puede crecer por slices verticales.",
    });
    expect(screen.getByRole("main")).toHaveFocus();
  });
});
