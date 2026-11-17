import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { renderApp } from "../../test/render-app";
import type { ProductSettingsOverview, ProductSettingValue } from "./administration-api";
import { changesOf, draftError } from "./product-settings-drafts";

function setting(
  key: ProductSettingValue["key"],
  group: ProductSettingValue["group"],
  value: number,
  extra: Partial<ProductSettingValue> = {},
): ProductSettingValue {
  return {
    key, group, kind: "integer", value, defaultValue: value, minimum: 0, maximum: 24, origin: "default", ...extra,
  };
}

function overview(horizon = 6, revision = 0): ProductSettingsOverview {
  return {
    revision,
    settings: [
      setting("featured.months-forward", "featured", 5),
      setting("upcoming-highlights.horizon-months", "upcoming_highlights", horizon, {
        defaultValue: 6, minimum: 1,
        ...(horizon === 6 ? {} : { origin: "operator", changedAt: "2026-11-12T10:00:00Z" }),
      }),
      setting("attention.editorial-weight", "attention", 0.5, { kind: "decimal", maximum: 1 }),
      setting("synchronization.upcoming.future-days", "synchronization", 180, { origin: "deployment", maximum: 730 }),
    ],
    coverage: {
      evaluatedOn: "2026-11-12", schedulingEnabled: true, acquisitionForwardDays: 180, acquisitionEnd: "2027-05-11",
      featuredLastMonth: "2027-04", featuredBeyondAcquisition: false,
      upcomingHorizonEnd: horizon === 6 ? "2027-05-11" : "2027-07-11", upcomingBeyondAcquisition: horizon !== 6,
    },
    recentChanges: horizon === 6 ? [] : [{
      revision: 1, key: "upcoming-highlights.horizon-months", previousValue: 6, newValue: horizon,
      restoredDefault: false, changedAt: "2026-11-12T10:00:00Z",
    }],
  };
}

function stub(answer: (body: unknown) => Response) {
  const puts: { body: unknown; csrf: string | null }[] = [];
  vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const request = input instanceof Request ? input : new Request(input);
    const url = new URL(request.url);
    if (url.pathname === "/api/v1/session") {
      return Response.json({ authenticated: true, csrfToken: "csrf-proof", capabilities: ["catalogue_administration"] });
    }
    if (url.pathname === "/api/v1/admin/catalogue/synchronization") return Response.json({ recentRuns: [] });
    if (url.pathname === "/api/v1/admin/catalogue/product-settings") {
      if (request.method === "PUT") {
        const body: unknown = await request.clone().json();
        puts.push({ body, csrf: request.headers.get("X-CSRF-Token") });
        return answer(body);
      }
      return Response.json(overview(puts.length > 0 ? 8 : 6, puts.length));
    }
    return Response.json({}, { status: 404 });
  }));
  return puts;
}

afterEach(() => vi.unstubAllGlobals());

describe("product settings administration", () => {
  it("changes the upcoming horizon from six to eight months and warns about acquisition", async () => {
    const puts = stub(() => Response.json(overview(8, 1)));
    const user = userEvent.setup();
    renderApp("/administracion/catalogo?tab=ajustes");

    const horizon = await screen.findByLabelText("Horizonte");
    expect(horizon).toHaveValue("6");
    expect(screen.getByText(/Por defecto 6 · entre 1 y 24/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Guardar cambios" })).toBeDisabled();

    await user.clear(horizon);
    await user.type(horizon, "8");
    expect(screen.getByText("1 cambio pendiente.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Guardar cambios" }));

    expect(await screen.findByText(/Cambios guardados/)).toBeInTheDocument();
    expect(puts).toEqual([{
      body: { revision: 0, changes: [{ key: "upcoming-highlights.horizon-months", value: 8 }] },
      csrf: "csrf-proof",
    }]);
    expect(screen.getByLabelText("Horizonte")).toHaveValue("8");
    expect(screen.getByText(/Cambiado por un operador/)).toBeInTheDocument();
    const warning = screen.getByText("Más allá de la sincronización").closest(".notice");
    expect(warning).not.toBeNull();
    expect(within(warning as HTMLElement).getByText(/Próximos destacados llega hasta el 11 jul 2027/)).toBeInTheDocument();
    const history = screen.getByRole("region", { name: "Cambios recientes" });
    expect(within(history).getByText("6 → 8")).toBeInTheDocument();
  });

  it("checks ranges and kinds before sending anything", async () => {
    const puts = stub(() => Response.json(overview()));
    const user = userEvent.setup();
    renderApp("/administracion/catalogo?tab=ajustes");

    const horizon = await screen.findByLabelText("Horizonte");
    await user.clear(horizon);
    await user.type(horizon, "25");
    expect(horizon).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByText("Debe estar entre 1 y 24.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Guardar cambios" })).toBeDisabled();
    expect(puts).toEqual([]);
  });

  it("re-reads the settings when another change came first", async () => {
    stub(() => Response.json(
      { code: "PRODUCT_SETTINGS_REVISION_CONFLICT", correlationId: "c-1" },
      { status: 409, headers: { "Content-Type": "application/problem+json" } },
    ));
    const user = userEvent.setup();
    renderApp("/administracion/catalogo?tab=ajustes");

    const editorial = await screen.findByLabelText("Peso editorial");
    await user.clear(editorial);
    await user.type(editorial, "0,25");
    await user.click(screen.getByRole("button", { name: "Guardar cambios" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/Otro cambio se guardó antes/);
    expect(screen.getByText("Sin cambios pendientes.")).toBeInTheDocument();
  });
});

describe("product settings drafts", () => {
  const horizon = setting("upcoming-highlights.horizon-months", "upcoming_highlights", 8, {
    defaultValue: 6, minimum: 1, origin: "operator",
  });
  const weight = setting("attention.editorial-weight", "attention", 0.5, { kind: "decimal", maximum: 1 });

  it("rejects values outside the setting's kind and range", () => {
    expect(draftError(horizon, "6.5")).toBe("Indica un número entero.");
    expect(draftError(horizon, "0")).toBe("Debe estar entre 1 y 24.");
    expect(draftError(horizon, "")).toBe("Indica un valor.");
    expect(draftError(weight, "0,12345")).toBe("Usa como máximo cuatro decimales.");
    expect(draftError(weight, "0,25")).toBeNull();
  });

  it("sends only real changes, and a restored default without a value", () => {
    expect(changesOf([horizon, weight], { "upcoming-highlights.horizon-months": "8" })).toEqual([]);
    expect(changesOf([horizon, weight], { "upcoming-highlights.horizon-months": null, "attention.editorial-weight": "0,3" }))
      .toEqual([{ key: "upcoming-highlights.horizon-months" }, { key: "attention.editorial-weight", value: 0.3 }]);
  });
});
