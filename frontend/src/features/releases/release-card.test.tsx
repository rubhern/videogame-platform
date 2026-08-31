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
  region: "Unknown",
  status: "Anunciado",
  provenance: "VideoGame Platform clickable prototype",
  isStale: true,
  freshness: "Datos locales desactualizados",
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
  it("attributes an approved provider cover", () => {
    renderCard();

    expect(screen.getByRole("img", { name: "Carátula de The Witcher IV" })).toHaveAttribute(
      "src",
      "https://images.igdb.com/igdb/image/upload/t_cover_big/coexample.webp",
    );
    expect(screen.getByRole("link", { name: "IGDB" })).toHaveAttribute(
      "href",
      "https://www.igdb.com/games/the-witcher-iv",
    );
  });

  it("falls back to the product-owned cover when the provider image cannot load", () => {
    renderCard();

    fireEvent.error(screen.getByRole("img", { name: "Carátula de The Witcher IV" }));

    expect(screen.getByRole("img", { name: "Carátula oficial no disponible" })).toHaveAttribute(
      "src",
      "/assets/covers/fallback.svg",
    );
    expect(screen.queryByRole("link", { name: "IGDB" })).not.toBeInTheDocument();
    expect(screen.getByText("Carátula oficial no disponible")).toBeInTheDocument();
  });

  it("keeps date precision, review and freshness explicit next to the game link", () => {
    renderCard();

    expect(screen.getByText("Fecha por confirmar")).toBeInTheDocument();
    const dataStates = screen.getByRole("list", {
      name: "Estado de los datos de The Witcher IV",
    });
    expect(dataStates).toHaveTextContent("Anunciado");
    expect(dataStates).toHaveTextContent("Datos locales desactualizados");
    expect(dataStates).toHaveTextContent("Información pendiente de revisión");
    expect(screen.getByRole("link", { name: "Ver The Witcher IV" })).toHaveAttribute(
      "href",
      "/games/the-witcher-iv",
    );
  });
});
