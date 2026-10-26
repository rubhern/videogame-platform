import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { LegalPage } from "./legal-pages";
import { LegalLinks } from "./legal-links";
import { legalRoutes } from "./legal-routes";

const wrap = (node: React.ReactNode) => render(<MemoryRouter>{node}</MemoryRouter>);

describe("legal publication boundary", () => {
  it("withholds draft routes and links from production", () => {
    expect(legalRoutes(false)).toEqual([]);
    wrap(<LegalLinks review={false} />);
    expect(screen.queryByRole("navigation", { name: "Información legal" })).not.toBeInTheDocument();
  });
  it("provides the four review routes and contact without a consent control", () => {
    expect(legalRoutes(true).map((route) => route.path)).toEqual(["privacidad", "condiciones", "aviso-legal", "cookies"]);
    wrap(<LegalLinks review />);
    expect(screen.getByRole("link", { name: "Privacidad" })).toHaveAttribute("href", "/privacidad");
    expect(screen.getByRole("link", { name: "Contacto" })).toHaveAttribute("href", "mailto:hola@gameometro.com");
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
  });
  it("renders the proposed identity and rights without the editorial checklist or supplier eligibility debate", () => {
    wrap(<LegalPage document="privacidad" />);
    expect(screen.getByRole("heading", { name: "Política de privacidad", level: 1 })).toBeVisible();
    expect(screen.getByLabelText("Estado del texto")).toHaveTextContent("No está en vigor");
    expect(screen.getByText(/Rubén Hernández López/)).toBeVisible();
    expect(screen.queryByText(/Internal editorial|professional.use|DPA approval/)).not.toBeInTheDocument();
  });
  it("includes the opt-in username hint in the labelled cookie inventory", () => {
    wrap(<LegalPage document="cookies" />);
    expect(screen.getByRole("table")).toHaveAccessibleName("Cookies de autenticación, seguridad e idioma");
    expect(screen.getAllByRole("row")).toHaveLength(9);
    expect(screen.getByRole("cell", { name: "KEYCLOAK_SESSION" })).toBeVisible();
    expect(screen.getByRole("cell", { name: "KEYCLOAK_REMEMBER_ME" })).toBeVisible();
    expect(screen.getByRole("cell", { name: "Persistente: 365 días" })).toBeVisible();
  });
});
