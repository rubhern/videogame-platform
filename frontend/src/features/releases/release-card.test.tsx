import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { ReleaseCard } from "./release-card";
import type { ReleaseContextGroup, ReleaseListItem } from "./releases-view-model";

function group(overrides: Partial<ReleaseContextGroup> = {}): ReleaseContextGroup {
  return {
    key: "day|2026-09-24|worldwide",
    date: "24 de septiembre de 2026",
    shortDate: "24 sep 2026",
    region: "Mundial",
    platforms: ["PlayStation 5"],
    isStale: false,
    review: false,
    releaseCount: 1,
    ...overrides,
  };
}

const providerCover = {
  kind: "provider" as const,
  url: "https://images.igdb.com/igdb/image/upload/t_cover_big/coexample.webp",
  alternativeText: "Carátula de The Witcher IV",
  attribution: { label: "IGDB", sourceUrl: "https://www.igdb.com/games/the-witcher-iv" },
};

function item(overrides: Partial<ReleaseListItem> = {}): ReleaseListItem {
  return {
    gameId: "game-witcher",
    slug: "the-witcher-iv",
    title: "The Witcher IV",
    releaseGroups: [group()],
    hiddenReleaseCount: 0,
    isStale: false,
    cover: providerCover,
    ...overrides,
  };
}

function renderCard(model: ReleaseListItem = item()) {
  return render(
    <MemoryRouter>
      <ReleaseCard item={model} />
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
  });

  it("falls back to the product-owned cover when the provider image cannot load", () => {
    renderCard();

    fireEvent.error(screen.getByRole("img", { name: "Carátula de The Witcher IV" }));

    expect(screen.getByRole("img", { name: "Carátula oficial no disponible" })).toHaveAttribute(
      "src",
      "/assets/covers/fallback.svg",
    );
  });

  it("shows the date, platform and region for a game with one release", () => {
    renderCard(item({ releaseGroups: [group({ platforms: ["PlayStation 5"] })] }));

    expect(screen.getByText("24 de septiembre de 2026")).toBeInTheDocument();
    // The cover chip repeats the date compactly for sight only; the row reads it in full.
    expect(screen.getByText("24 sep 2026")).toHaveAttribute("aria-hidden", "true");
    expect(screen.getByText("PlayStation 5 · Mundial")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /lanzamientos más/ })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "The Witcher IV" })).toHaveAttribute(
      "href",
      "/games/game-witcher/the-witcher-iv",
    );
  });

  it("joins the platforms that share the same date and region into one row", () => {
    renderCard(
      item({
        releaseGroups: [
          group({ platforms: ["PlayStation 5", "Windows PC", "Xbox Series X|S"], releaseCount: 3 }),
        ],
      }),
    );

    expect(
      screen.getByText("PlayStation 5 · Windows PC · Xbox Series X|S · Mundial"),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /lanzamientos más/ })).not.toBeInTheDocument();
  });

  it("hides the other release groups behind a control counting hidden releases", async () => {
    const user = userEvent.setup();
    renderCard(
      item({
        releaseGroups: [
          group({ platforms: ["PlayStation 5"], releaseCount: 1 }),
          group({
            key: "day|2026-09-24|worldwide-2",
            date: "24 de septiembre de 2026",
            region: "Mundial",
            platforms: ["Windows PC", "Xbox Series X|S"],
            releaseCount: 2,
          }),
          group({
            key: "quarter|2026-Q4|europe",
            date: "4.º trimestre de 2026",
            region: "Europa",
            platforms: ["Nintendo Switch 2"],
            releaseCount: 1,
          }),
        ],
        hiddenReleaseCount: 3,
      }),
    );

    // Only the first group is a visible row.
    expect(screen.getByText("PlayStation 5 · Mundial")).toBeInTheDocument();
    const trigger = screen.getByRole("button", { name: "+ 3 lanzamientos más" });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    // Hidden content is not rendered until opened.
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    await user.click(trigger);

    const dialog = screen.getByRole("dialog", { name: "Otros lanzamientos de The Witcher IV" });
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(dialog).toHaveFocus();
    // The popover preserves date/precision, grouped platforms and region for every hidden release.
    expect(within(dialog).getByText("Windows PC · Xbox Series X|S · Mundial")).toBeInTheDocument();
    expect(within(dialog).getByText("Nintendo Switch 2 · Europa")).toBeInTheDocument();
    expect(within(dialog).getByText("4.º trimestre de 2026")).toBeInTheDocument();
  });

  it("uses a singular label when exactly one release is hidden", () => {
    renderCard(
      item({
        releaseGroups: [
          group({ platforms: ["PlayStation 5"] }),
          group({ key: "quarter|2026-Q4|europe", platforms: ["Windows PC"], region: "Europa" }),
        ],
        hiddenReleaseCount: 1,
      }),
    );

    expect(screen.getByRole("button", { name: "+ 1 lanzamiento más" })).toBeInTheDocument();
  });

  it("closes the popover on Escape and returns focus to the trigger", async () => {
    const user = userEvent.setup();
    renderCard(
      item({
        releaseGroups: [
          group({ platforms: ["PlayStation 5"] }),
          group({ key: "quarter|2026-Q4|europe", platforms: ["Windows PC"], region: "Europa" }),
        ],
        hiddenReleaseCount: 1,
      }),
    );

    const trigger = screen.getByRole("button", { name: "+ 1 lanzamiento más" });
    await user.click(trigger);
    expect(screen.getByRole("dialog")).toBeInTheDocument();

    await user.keyboard("{Escape}");

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("keeps date precision and the review notice for the visible release", () => {
    renderCard(
      item({
        releaseGroups: [
          group({ platforms: ["Windows PC"], region: "Sin región confirmada", review: true }),
        ],
      }),
    );

    expect(
      screen.getByRole("list", { name: "Estado de los datos de The Witcher IV" }),
    ).toHaveTextContent("Información pendiente de revisión");
  });

  it("omits the review notice when nothing needs review", () => {
    renderCard(item({ releaseGroups: [group({ review: false })] }));

    expect(screen.queryByText("Información pendiente de revisión")).not.toBeInTheDocument();
  });
});
