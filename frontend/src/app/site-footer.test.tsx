import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";

import { SiteFooter } from "./site-footer";

const renderFooter = () => render(<MemoryRouter><SiteFooter /></MemoryRouter>);

describe("site footer", () => {
  afterEach(() => vi.useRealTimers());

  it("names the brand and carries the slogan and signature", () => {
    renderFooter();
    const footer = screen.getByRole("contentinfo");
    expect(within(footer).getByText("Gameómetro", { selector: ".sr-only" })).toBeInTheDocument();
    expect(within(footer).getByText("Un hombre elige.")).toBeVisible();
    expect(within(footer).getByText("Un esclavo obedece.")).toBeVisible();
    expect(within(footer).getByText("Nosotros te ayudamos a elegir.")).toBeVisible();
    expect(within(footer).getByText(/Ideado, desarrollado y mantenido por/)).toHaveTextContent(
      "Ideado, desarrollado y mantenido por Rubén Hernández",
    );
  });

  it("states the current year and links the data source to IGDB", () => {
    vi.useFakeTimers({ toFake: ["Date"], now: new Date("2027-03-01T12:00:00Z") });
    renderFooter();
    expect(screen.getByText("© 2027 Gameómetro")).toBeVisible();
    expect(screen.getByText(/Datos de videojuegos:/)).toContainElement(
      screen.getByRole("link", { name: "IGDB" }),
    );
    expect(screen.getByRole("link", { name: "IGDB" })).toHaveAttribute("href", "https://www.igdb.com/");
  });

  it("makes the source credits available through a native disclosure", async () => {
    const user = userEvent.setup();
    renderFooter();
    const summary = screen.getByText("Créditos de marcas");
    await user.click(summary);
    expect(summary.closest("details")).toHaveAttribute("open");
    expect(screen.getByText(/Tux: Larry Ewing, Garrett LeSage e IFo Hancroft/)).toBeVisible();
    expect(screen.getByRole("link", { name: "Creative Commons 3.0 Attribution License" }))
      .toHaveAttribute("href", "https://creativecommons.org/licenses/by/3.0/");
  });
});
