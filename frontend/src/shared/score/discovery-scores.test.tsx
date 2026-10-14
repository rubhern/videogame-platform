import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { DiscoveryScores } from "./discovery-scores";
import { toDiscoveryScores } from "./discovery-scores-model";
import type { components } from "../api/generated/schema";

type Scores = components["schemas"]["DiscoveryScores"];
const external: NonNullable<Scores["external"]> = {
  source: "igdb", kind: "player", value: 8.8, scale: 10, count: 1,
  attribution: { label: "Jugadores IGDB", sourceUrl: "https://www.igdb.com/games/original" },
  observedAt: "2020-01-01T00:00:00Z",
};

function show(scores: Scores | undefined, layout: "compact" | "hero" | "featured" = "compact") {
  return render(<DiscoveryScores scores={toDiscoveryScores(scores)} title="Original" layout={layout} />);
}

describe("discovery scores", () => {
  it.each([undefined, { community: { status: "available", mean: null, count: 0 } },
    { community: { status: "unavailable", reasonCode: "RATING_STATISTICS_READ_FAILED" } }] satisfies (Scores | undefined)[])(
    "omits the whole score block when no usable source exists (%j)", (scores) => {
      show(scores);
      expect(screen.queryByRole("list", { name: "Puntuaciones de Original" })).not.toBeInTheDocument();
    },
  );

  it.each(["compact", "hero", "featured"] as const)("keeps both sources, scale and one vote in %s", (layout) => {
    show({ community: { status: "available", mean: 9, count: 1 }, external }, layout);
    const scores = screen.getByRole("list", { name: "Puntuaciones de Original" });
    expect(within(scores).getAllByRole("listitem")).toHaveLength(2);
    expect(within(scores).getByText("Gameómetro ·", { exact: false })).toBeInTheDocument();
    expect(within(scores).getByLabelText("9,0 de 10")).toBeVisible();
    expect(within(scores).getByLabelText("8,8 de 10")).toBeVisible();
    expect(within(scores).getAllByText("1 valoración")).toHaveLength(2);
    expect(within(scores).getByRole("link", { name: "Jugadores IGDB: fuente de la puntuación de Original" }))
      .toHaveAttribute("href", external.attribution.sourceUrl);
    expect(scores.querySelector("img")).toHaveAttribute("src", "/assets/providers/igdb.svg");
    expect(within(scores).getAllByText("Ardiendo")).toHaveLength(1);
  });

  it("shows community alone without an invented external score", () => {
    show({ community: { status: "available", mean: 7.5, count: 1 } });
    expect(screen.getAllByRole("listitem")).toHaveLength(1);
    expect(screen.getByLabelText("7,5 de 10")).toBeVisible();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("keeps an older IGDB score and does not treat unavailable community as zero", () => {
    show({ community: { status: "unavailable", reasonCode: "RATING_STATISTICS_READ_FAILED" }, external });
    expect(screen.getAllByRole("listitem")).toHaveLength(1);
    expect(screen.getByLabelText("8,8 de 10")).toBeVisible();
    expect(screen.queryByText("Gameómetro", { exact: false })).not.toBeInTheDocument();
  });

  it("keeps a real external zero with one vote and exact accessible compact counts", () => {
    show({ community: { status: "available", mean: 6.5, count: 123456 }, external: { ...external, value: 0 } });
    expect(screen.getByLabelText("0,0 de 10")).toBeVisible();
    expect(screen.getByText("123.456 valoraciones")).toBeInTheDocument();
    expect(screen.getByText("(123,5 mil)")).toBeVisible();
  });

  it("rejects invalid pairs without hiding the other valid source", () => {
    show({ community: { status: "available", mean: 9, count: 1 }, external: { ...external, count: 0 } });
    expect(screen.getAllByRole("listitem")).toHaveLength(1);
    expect(screen.getByLabelText("9,0 de 10")).toBeVisible();
  });

  it.each([[999, "999"], [1000, "+1K"], [3548, "+3K"], [9999, "+9K"],
    [10000, "+10K"], [123456, "+123K"]] as const)(
    "formats %i votes only in featured cards as %s, keeping both exact counts", (count, visible) => {
      show({ community: { status: "available", mean: 9, count }, external: { ...external, count } }, "featured");
      expect(screen.getAllByText(`(${visible})`)).toHaveLength(2);
      const exact = `${new Intl.NumberFormat("es-ES", { useGrouping: "always" }).format(count)} valoraciones`;
      expect(screen.getAllByText(exact)).toHaveLength(2);
      expect(screen.getAllByTitle(exact)).toHaveLength(2);
    },
  );

  it.each(["compact", "hero"] as const)("preserves existing four-digit counts in %s", (layout) => {
    show({ community: { status: "available", mean: 9, count: 3548 } }, layout);
    expect(screen.getByText(layout === "hero" ? "3.548 valoraciones" : "(3.548)")).toBeInTheDocument();
    expect(screen.queryByText("(+3K)")).not.toBeInTheDocument();
  });
});
