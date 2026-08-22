import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { render } from "@testing-library/react";

import { ReleasesShell } from "./releases-shell";

describe("releases shell", () => {
  it("announces loading with an accessible shell name", () => {
    render(
      <MemoryRouter>
        <ReleasesShell state={{ status: "loading" }} />
      </MemoryRouter>,
    );

    expect(screen.getByRole("region", { name: "Lanzamientos recientes" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Cargando lanzamientos");
  });

  it("renders a representative release and a keyboard-usable game link", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <ReleasesShell
          state={{
            status: "success",
            items: [
              {
                gameId: "game-pragmata",
                slug: "pragmata",
                title: "Pragmata",
                date: "2.º trimestre de 2026",
                platform: "Windows PC",
                region: "Worldwide",
                provenance: "VideoGame Platform clickable prototype",
                freshness: "Datos locales desactualizados",
                review: null,
              },
            ],
          }}
        />
      </MemoryRouter>,
    );

    expect(screen.getByRole("list", { name: "Lanzamientos recientes" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Pragmata" })).toBeInTheDocument();
    expect(screen.getByText("2.º trimestre de 2026")).toBeInTheDocument();
    expect(screen.getByText("Datos locales desactualizados")).toBeInTheDocument();

    await user.tab();
    const link = screen.getByRole("link", { name: "Ver Pragmata" });
    expect(link).toHaveFocus();
    expect(link).toHaveAttribute("href", "/games/pragmata");
  });

  it("announces an empty successful response", () => {
    render(
      <MemoryRouter>
        <ReleasesShell state={{ status: "empty" }} />
      </MemoryRouter>,
    );

    expect(screen.getByRole("status")).toHaveTextContent(
      "No hay lanzamientos recientes en este momento.",
    );
  });

  it("announces a failure separately from an empty response", () => {
    render(
      <MemoryRouter>
        <ReleasesShell
          state={{
            status: "error",
            message: "El catálogo local todavía no está disponible.",
          }}
        />
      </MemoryRouter>,
    );

    expect(screen.getByRole("alert")).toHaveTextContent(
      "El catálogo local todavía no está disponible.",
    );
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
  });
});
