import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import canonical from "../../../../docs/legal/public-texts-es.md?raw";

import { LegalPage } from "./legal-pages";
import { LegalLinks } from "./legal-links";
import { legalRoutes } from "./legal-routes";

const wrap = (node: React.ReactNode) => render(<MemoryRouter>{node}</MemoryRouter>);

describe("legal publication boundary", () => {
  it.each([
    ["privacidad", "Política de privacidad"], ["condiciones", "Condiciones de uso"],
    ["aviso-legal", "Aviso legal"], ["cookies", "Cookies y almacenamiento del navegador"],
  ] as const)("renders all approved public wording for %s", (document, title) => {
    const section = canonical.split(`## ${title}\n`)[1];
    if (section === undefined) throw new Error(`Missing approved section: ${title}`);
    const body = section.split("\n## ")[0] ?? "";
    const { container } = wrap(<LegalPage document={document} />);
    const visible = (container.textContent ?? "").replace(/\s+/g, " ");
    for (const block of body.trim().split(/\n\s*\n/)) {
      if (block.startsWith("**Texto aprobado")) continue;
      const text = block.replace(/^### /, "").replace(/\[([^\]]+)\]\([^)]+\)/g, "$1").replaceAll("`", "");
      if (text.startsWith("|")) {
        for (const row of text.split("\n").filter(row => !/^\|[-| ]+\|$/.test(row))) {
          for (const cell of row.split("|").slice(1, -1)) expect(visible).toContain(cell.trim());
        }
      } else expect(visible).toContain(text.replace(/\s+/g, " "));
    }
  });

  it("provides the four public routes and contact in every build without a consent control", () => {
    expect(legalRoutes().map((route) => route.path)).toEqual(["privacidad", "condiciones", "aviso-legal", "cookies"]);
    wrap(<LegalLinks />);
    const legal = screen.getByRole("navigation", { name: "Legal" });
    expect(within(legal).getAllByRole("link").map((link) => [link.textContent, link.getAttribute("href")])).toEqual([
      ["Aviso legal", "/aviso-legal"], ["Privacidad", "/privacidad"], ["Cookies", "/cookies"], ["Condiciones de uso", "/condiciones"],
    ]);
    // Every legal destination is a declared route.
    const routes = legalRoutes().map((route) => `/${route.path}`);
    for (const link of within(legal).getAllByRole("link")) expect(routes).toContain(link.getAttribute("href"));
    const product = screen.getByRole("navigation", { name: "Gameómetro" });
    expect(within(product).getAllByRole("link").map((link) => [link.textContent, link.getAttribute("href")])).toEqual([
      ["Sobre Gameómetro", "/sobre-gameometro"], ["Contacto", "mailto:hola@gameometro.com"],
    ]);
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
  });
  it("renders the approved identity and rights without editorial metadata", () => {
    wrap(<LegalPage document="privacidad" />);
    expect(screen.getByRole("heading", { name: "Política de privacidad", level: 1 })).toBeVisible();
    expect(screen.queryByLabelText("Estado del texto")).not.toBeInTheDocument();
    expect(screen.getByText(/Rubén Hernández López/)).toBeVisible();
    expect(screen.queryByText(/Internal editorial|Texto aprobado por el titular|Status:|Borrador|Plantilla administrativa/)).not.toBeInTheDocument();
  });
  it("includes the opt-in username hint in the labelled cookie inventory", () => {
    wrap(<LegalPage document="cookies" />);
    expect(screen.getByRole("table")).toHaveAccessibleName("Cookies de autenticación, seguridad e idioma");
    expect(screen.getAllByRole("row")).toHaveLength(10);
    expect(screen.getByRole("cell", { name: "gameometro_deletion" })).toBeVisible();
    expect(screen.getByRole("cell", { name: "KEYCLOAK_SESSION" })).toBeVisible();
    expect(screen.getByRole("cell", { name: "KEYCLOAK_REMEMBER_ME" })).toBeVisible();
    expect(screen.getByRole("cell", { name: "Persistente: 365 días" })).toBeVisible();
  });
});
