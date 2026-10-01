import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { GameMeter } from "./game-meter";

const meter = (value: number | null) =>
  render(<GameMeter value={value} />).container.querySelector("svg");

describe("GameMeter", () => {
  it("is decorative: the number beside it carries the reading", () => {
    expect(meter(7.3)).toHaveAttribute("aria-hidden", "true");
  });

  it("takes the temperature of the score it reads", () => {
    expect(meter(1)).toHaveAttribute("data-thermal", "freeze");
    expect(meter(7.3)).toHaveAttribute("data-thermal", "hot");
    expect(meter(10)).toHaveAttribute("data-thermal", "burn");
  });

  it("turns its needle in proportion to the score, up to the G's terminal at 10", () => {
    expect(meter(5)?.style.getPropertyValue("--meter-turn")).toBe("140deg");
    expect(meter(10)?.style.getPropertyValue("--meter-turn")).toBe("280deg");
  });

  it("rests without a needle or temperature when there is no score", () => {
    const empty = meter(null);
    expect(empty).not.toHaveAttribute("data-thermal");
    expect(empty?.querySelector(".game-meter-needle")).toBeNull();
  });
});
