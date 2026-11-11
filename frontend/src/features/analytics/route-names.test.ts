import { describe, expect, it } from "vitest";

import { reportedRoute } from "./route-names";

describe("reportedRoute", () => {
  it("reports product routes by name and template", () => {
    expect(reportedRoute("/")).toEqual({ name: "home", path: "/" });
    expect(reportedRoute("/lanzamientos/destacados")).toEqual({ name: "featured", path: "/lanzamientos/destacados" });
    expect(reportedRoute("/topometro/")).toEqual({ name: "topometro", path: "/topometro" });
    expect(reportedRoute("/cookies")).toEqual({ name: "legal", path: "/cookies" });
    expect(reportedRoute("/sobre-gameometro")).toEqual({ name: "about", path: "/sobre-gameometro" });
    expect(reportedRoute("/contacto")).toEqual({ name: "contact", path: "/contacto" });
  });

  it("keeps the public game slug and drops the internal game ID", () => {
    expect(reportedRoute("/games/30000000-0000-4000-8000-000000000005/resident-evil-requiem")).toEqual({
      name: "game_detail",
      path: "/games/resident-evil-requiem",
    });
    expect(reportedRoute("/games/30000000-0000-4000-8000-000000000005")).toEqual({
      name: "game_detail",
      path: "/games",
    });
    expect(reportedRoute("/games/id/Not%20A%20Slug")).toEqual({ name: "game_detail", path: "/games" });
  });

  it("keeps a public platform key only on platform destinations", () => {
    expect(reportedRoute("/plataformas/playstation-5/topometro")).toEqual({
      name: "platform_topometro",
      path: "/plataformas/playstation-5/topometro",
    });
    expect(reportedRoute("/plataformas/playstation-5/otra")).toEqual({ name: "not_found", path: "/404" });
  });

  it("never reports administration and folds unknown paths into one not-found page", () => {
    expect(reportedRoute("/administracion/catalogo")).toBeNull();
    expect(reportedRoute("/administracion")).toBeNull();
    expect(reportedRoute("/usuario/alice@example.com")).toEqual({ name: "not_found", path: "/404" });
  });
});
