import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { renderApp } from "../../test/render-app";
import { exportProfileCard, paintProfileCard } from "./profile-share-image-painter";

// JSDOM cannot draw on a canvas; the browser spec proves the painted PNG. Here the painter stands
// in so the studio's choices, privacy checks and sharing paths can be observed.
vi.mock("./profile-share-image-painter", () => ({
  loadCardAssets: vi.fn(() => Promise.resolve({})),
  paintProfileCard: vi.fn(() => Promise.resolve(true)),
  exportProfileCard: vi.fn(() => Promise.resolve(new Blob(["png"], { type: "image/png" }))),
}));

const alias = "luna-8";
const attribution = { label: "IGDB", sourceUrl: "https://www.igdb.com/games/example" };
const cover = { kind: "provider", url: "https://images.igdb.com/cover.jpg", alternativeText: "Portada", attribution };
const rated = [
  { gameId: "30000000-0000-4000-8000-000000000001", slug: "alfa", canonicalTitle: "Alfa", primaryCover: cover, score: 9.5 },
  { gameId: "30000000-0000-4000-8000-000000000002", slug: "beta", canonicalTitle: "Beta", primaryCover: cover, score: 6 },
];
const wished = [{ gameId: "30000000-0000-4000-8000-000000000003", slug: "gamma", canonicalTitle: "Gamma", primaryCover: cover }];

type Bff = { published: boolean; sections: string[]; reads: string[] };

function bff(state: Bff) {
  vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockImplementation(async (input) => {
    const url = new URL((input as Request).url);
    const path = url.pathname.replace("/api/v1", "");
    if (path === "/session") return Response.json({ authenticated: true, csrfToken: "proof" });
    if (path === "/me/profile") return Response.json({ email: "private@example.test", deletionAvailable: true });
    if (path === "/me/public-profile") {
      return Response.json({
        alias, published: state.published, revision: "00000000-0000-4000-8000-000000000001",
        shareRatings: state.published && state.sections.includes("ratings"),
        shareWishlist: state.published && state.sections.includes("wishlist"),
      });
    }
    if (path.startsWith("/profiles/")) {
      state.reads.push(`${path}${url.search}`);
      const missing = Response.json({ code: "PUBLIC_PROFILE_NOT_FOUND" }, {
        status: 404, headers: { "Content-Type": "application/problem+json" },
      });
      if (!state.published) return missing;
      if (path === `/profiles/${alias}`) return Response.json({ alias, sections: state.sections });
      if (path.endsWith("/statistics")) {
        return Response.json({
          ...(state.sections.includes("ratings")
            ? { ratings: { total: 2, mean: 7.8, lowest: 6, highest: 9.5, distribution: [0, 0, 0, 0, 0, 1, 0, 0, 0, 1] } } : {}),
          ...(state.sections.includes("wishlist") ? { wishlistCount: 1 } : {}),
        });
      }
      const section = path.endsWith("/wishlist") ? "wishlist" : "ratings";
      if (!state.sections.includes(section)) return missing;
      const items = section === "ratings" ? rated : wished;
      return Response.json({ items, page: { number: 1, size: 6, totalItems: items.length, totalPages: 1 } });
    }
    return Response.json({ code: "NOT_FOUND" }, { status: 404 });
  }));
}

const saved: { name: string }[] = [];

beforeEach(() => {
  saved.length = 0;
  vi.mocked(paintProfileCard).mockClear();
  vi.mocked(exportProfileCard).mockClear();
  // JSDOM has no object URLs; the download link only needs one to click.
  Object.defineProperty(URL, "createObjectURL", { configurable: true, value: vi.fn(() => "blob:share-card") });
  Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: vi.fn() });
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
    saved.push({ name: this.download });
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  Reflect.deleteProperty(navigator, "share");
  Reflect.deleteProperty(navigator, "canShare");
  Reflect.deleteProperty(URL, "createObjectURL");
  Reflect.deleteProperty(URL, "revokeObjectURL");
});

async function openStudio() {
  const user = userEvent.setup();
  renderApp("/mi-perfil");
  const sharing = await screen.findByRole("region", { name: "Perfil público" });
  const toggle = await within(sharing).findByRole("button", { name: "Crear imagen para compartir" });
  return { user, sharing, toggle };
}

async function readyPreview() {
  const image = await screen.findByRole("img", { name: /^Perfil de @luna-8 en Gameómetro/ });
  await waitFor(() => expect(screen.getByRole("button", { name: "Descargar imagen" })).toBeEnabled());
  return image;
}

describe("Public profile image", () => {
  it("offers no image for a private profile", async () => {
    bff({ published: false, sections: [], reads: [] });
    renderApp("/mi-perfil");
    const sharing = await screen.findByRole("region", { name: "Perfil público" });
    await within(sharing).findByRole("checkbox", { name: "Publicar mi perfil" });
    expect(within(sharing).queryByRole("button", { name: "Crear imagen para compartir" })).not.toBeInTheDocument();
  });

  it("reads nothing public until the player opens the preview, then describes it in words", async () => {
    const state = { published: true, sections: ["ratings", "wishlist"], reads: [] as string[] };
    bff(state);
    const { user, toggle } = await openStudio();

    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(state.reads).toEqual([]);
    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(document.getElementById(toggle.getAttribute("aria-controls") ?? "")).toBeVisible();

    const image = await readyPreview();
    expect(image).toHaveAccessibleName(
      "Perfil de @luna-8 en Gameómetro, en formato publicación. "
      + "Mis notas más altas: 1.º Alfa, 9,5 de 10, Ardiendo; 2.º Beta, 6 de 10, Templado. "
      + "Así puntúo: 2 juegos puntuados, media de 7,8, Caliente; reparto: 1 con un 10, 1 con un 6. "
      + `Mis deseados: 1 juego, entre ellos Gamma. ${window.location.host}/u/luna-8. Datos e imágenes: IGDB.`,
    );
    expect(state.reads.every((read) => read.startsWith(`/profiles/${alias}`))).toBe(true);
    expect(image.getAttribute("aria-label")).not.toContain("private@example.test");
    const recipients = screen.getByText(/Quien la reciba podrá conservarla y reenviarla/);
    expect(recipients).not.toBeVisible();
    const summary = screen.getByText((_, element) =>
      element?.tagName === "SUMMARY" && element.textContent === "Más info sobre la imagen");
    await user.click(summary);
    expect(summary.closest("details")).toHaveAttribute("open");
    expect(recipients).toBeVisible();

    await user.click(screen.getByRole("checkbox", { name: "Así puntúo" }));
    await waitFor(() => expect(image.getAttribute("aria-label")).not.toContain("Así puntúo"));
    await user.click(screen.getByRole("radio", { name: /Historia/ }));
    await waitFor(() => expect(image.getAttribute("aria-label")).toContain("en formato historia"));
  });

  it("offers only the content the public profile shares now", async () => {
    bff({ published: true, sections: ["ratings"], reads: [] });
    const { user, toggle } = await openStudio();
    await user.click(toggle);
    await readyPreview();

    const wishlist = screen.getByRole("checkbox", { name: "Mis deseados" });
    expect(wishlist).toBeDisabled();
    expect(wishlist).not.toBeChecked();
    expect(wishlist).toHaveAccessibleDescription("Tu perfil no muestra tus deseados.");
    expect(screen.getByRole("checkbox", { name: "Juegos puntuados" })).toHaveAccessibleDescription(
      "La portada, el título y tu nota de hasta tres juegos.",
    );
  });

  it("checks the profile again and downloads the image where native sharing is unavailable", async () => {
    const state = { published: true, sections: ["ratings", "wishlist"], reads: [] as string[] };
    bff(state);
    const { user, toggle } = await openStudio();
    await user.click(toggle);
    await readyPreview();
    expect(screen.queryByRole("button", { name: "Compartir imagen" })).not.toBeInTheDocument();
    const profileReads = () => state.reads.filter((read) => read === `/profiles/${alias}`).length;
    const before = profileReads();

    await user.click(screen.getByRole("button", { name: "Descargar imagen" }));

    expect(await screen.findByText(/Imagen descargada como gameometro-luna-8-publicacion\.png/)).toBeVisible();
    expect(profileReads()).toBe(before + 1);
    expect(exportProfileCard).toHaveBeenCalledTimes(1);
    expect(saved).toEqual([{ name: "gameometro-luna-8-publicacion.png" }]);
  });

  it("creates no image when the profile was made private after the preview", async () => {
    const state = { published: true, sections: ["ratings", "wishlist"], reads: [] as string[] };
    bff(state);
    const { user, toggle } = await openStudio();
    await user.click(toggle);
    await readyPreview();

    state.published = false;
    await user.click(screen.getByRole("button", { name: "Descargar imagen" }));

    expect(await screen.findByText("Tu perfil no es público ahora mismo, así que no se puede crear ninguna imagen de él."))
      .toBeVisible();
    expect(screen.queryByRole("img", { name: /^Perfil de @luna-8/ })).not.toBeInTheDocument();
    expect(exportProfileCard).not.toHaveBeenCalled();
    expect(saved).toEqual([]);
  });

  it("asks for a new look when a chosen section stopped being shared", async () => {
    const state = { published: true, sections: ["ratings", "wishlist"], reads: [] as string[] };
    bff(state);
    const { user, toggle } = await openStudio();
    await user.click(toggle);
    await readyPreview();

    state.sections = ["ratings"];
    await user.click(screen.getByRole("button", { name: "Descargar imagen" }));

    expect(await screen.findByText("Lo que compartes en tu perfil ha cambiado. Revisa la vista previa antes de compartirla."))
      .toBeVisible();
    expect(exportProfileCard).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.getByRole("checkbox", { name: "Mis deseados" })).toBeDisabled());
    expect(screen.getByRole("img", { name: /^Perfil de @luna-8/ }).getAttribute("aria-label")).not.toContain("Mis deseados");
  });

  it("hands the image and the profile link to the native share sheet", async () => {
    bff({ published: true, sections: ["ratings", "wishlist"], reads: [] });
    const share = vi.fn<(data: ShareData) => Promise<void>>(() => Promise.resolve());
    Object.defineProperty(navigator, "canShare", { configurable: true, value: () => true });
    Object.defineProperty(navigator, "share", { configurable: true, value: share });
    const { user, toggle } = await openStudio();
    await user.click(toggle);
    await readyPreview();

    await user.click(screen.getByRole("button", { name: "Compartir imagen" }));

    expect(await screen.findByText("Imagen enviada a la aplicación que elegiste.")).toBeVisible();
    const [data] = share.mock.calls[0] ?? [];
    expect(data?.files?.map((file) => [file.name, file.type])).toEqual([["gameometro-luna-8-publicacion.png", "image/png"]]);
    expect(data?.text).toBe(`¡Mira mi perfil de jugador en Gameómetro!\n${window.location.origin}/u/luna-8`);
    expect(saved).toEqual([]);
  });

  it("stays quiet when the player cancels the share sheet and offers a download when sharing fails", async () => {
    bff({ published: true, sections: ["ratings"], reads: [] });
    const share = vi.fn<(data: ShareData) => Promise<void>>()
      .mockRejectedValueOnce(new DOMException("cancelled", "AbortError"))
      .mockRejectedValueOnce(new DOMException("blocked", "NotAllowedError"));
    Object.defineProperty(navigator, "canShare", { configurable: true, value: () => true });
    Object.defineProperty(navigator, "share", { configurable: true, value: share });
    const { user, toggle } = await openStudio();
    await user.click(toggle);
    await readyPreview();

    await user.click(screen.getByRole("button", { name: "Compartir imagen" }));
    await waitFor(() => expect(share).toHaveBeenCalledTimes(1));
    expect(screen.queryByText(/enviada/)).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Compartir imagen" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "No se pudo compartir la imagen. Descárgala y compártela desde tus archivos.",
    );
    expect(screen.getByRole("button", { name: "Descargar imagen" })).toBeEnabled();
  });

  it("reports an image the browser cannot export without sharing anything", async () => {
    bff({ published: true, sections: ["ratings"], reads: [] });
    vi.mocked(exportProfileCard).mockResolvedValueOnce(null);
    const { user, toggle } = await openStudio();
    await user.click(toggle);
    await readyPreview();

    await user.click(screen.getByRole("button", { name: "Descargar imagen" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("No se pudo crear la imagen en este navegador.");
    expect(saved).toEqual([]);
  });

  it("copies the message and link as the fallback beside a downloaded image", async () => {
    bff({ published: true, sections: ["ratings"], reads: [] });
    // user-event provides the clipboard the page writes to.
    const { user, toggle } = await openStudio();
    await user.click(toggle);
    await readyPreview();

    await user.click(screen.getByRole("button", { name: "Copiar enlace" }));

    expect(await screen.findByText("Mensaje y enlace copiados. Pégalos junto a la imagen.")).toBeVisible();
    expect(await navigator.clipboard.readText())
      .toBe(`¡Mira mi perfil de jugador en Gameómetro!\n${window.location.origin}/u/luna-8`);
  });
});
