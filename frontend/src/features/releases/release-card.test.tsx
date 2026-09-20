import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { ReleaseCard } from "./release-card";
import type { ReleaseListItem } from "./releases-view-model";

const baseItem: ReleaseListItem = {
  releaseId: "release-witcher-pc",
  gameId: "game-witcher",
  slug: "the-witcher-iv",
  title: "The Witcher IV",
  date: "Fecha por confirmar",
  platform: "Windows PC",
  region: "Sin región confirmada",
  isStale: true,
  review: "Información pendiente de revisión",
  cover: {
    kind: "provider",
    url: "https://images.igdb.com/igdb/image/upload/t_cover_big/coexample.webp",
    alternativeText: "Carátula de The Witcher IV",
    attribution: { label: "IGDB", sourceUrl: "https://www.igdb.com/games/the-witcher-iv" },
  },
};

function renderCard(item: ReleaseListItem = baseItem) {
  return render(
    <MemoryRouter>
      <ReleaseCard item={item} />
    </MemoryRouter>,
  );
}

describe("release card", () => {
  it("shows an approved provider cover without the caption the game page owns", () => {
    renderCard();

    expect(screen.getByRole("img", { name: "Carátula de The Witcher IV" })).toHaveAttribute(
      "src",
      "https://images.igdb.com/igdb/image/upload/t_cover_big/coexample.webp",
    );
    expect(screen.queryByRole("link", { name: "IGDB" })).not.toBeInTheDocument();
    expect(screen.queryByText(/Carátula:/)).not.toBeInTheDocument();
  });

  it("falls back to the product-owned cover when the provider image cannot load", () => {
    renderCard();

    fireEvent.error(screen.getByRole("img", { name: "Carátula de The Witcher IV" }));

    expect(screen.getByRole("img", { name: "Carátula oficial no disponible" })).toHaveAttribute(
      "src",
      "/assets/covers/fallback.svg",
    );
    expect(screen.queryByText("Carátula oficial no disponible")).not.toBeInTheDocument();
  });

  it("shows a new approved cover after the previous URL failed", () => {
    const { rerender } = renderCard();
    fireEvent.error(screen.getByRole("img", { name: "Carátula de The Witcher IV" }));

    const replacement: ReleaseListItem = {
      ...baseItem,
      cover: {
        kind: "provider",
        url: "https://images.igdb.com/igdb/image/upload/t_cover_big/coreplacement.webp",
        alternativeText: "Nueva carátula de The Witcher IV",
        attribution: { label: "IGDB", sourceUrl: "https://www.igdb.com/games/the-witcher-iv" },
      },
    };
    rerender(<MemoryRouter><ReleaseCard item={replacement} /></MemoryRouter>);

    expect(screen.getByRole("img", { name: "Nueva carátula de The Witcher IV" })).toHaveAttribute(
      "src", replacement.cover.url,
    );
    expect(screen.queryByRole("img", { name: "Carátula oficial no disponible" })).not.toBeInTheDocument();
  });

  it("keeps date precision and the review notice, and links the game from its title", () => {
    renderCard();

    expect(screen.getByText("Fecha por confirmar")).toBeInTheDocument();
    expect(
      screen.getByRole("list", { name: "Estado de los datos de The Witcher IV" }),
    ).toHaveTextContent("Información pendiente de revisión");
    expect(screen.queryByText("Datos locales desactualizados")).not.toBeInTheDocument();
    expect(screen.queryByText(/Fuente:/)).not.toBeInTheDocument();
    expect(screen.queryByText("Ver ficha →")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "The Witcher IV" })).toHaveAttribute(
      "href",
      "/games/game-witcher/the-witcher-iv",
    );
  });

  it("omits the data-state list when nothing needs review", () => {
    renderCard({ ...baseItem, review: null });

    expect(screen.queryByRole("list")).not.toBeInTheDocument();
  });
});
