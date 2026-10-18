import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { ReleaseWindowScale } from "./release-window-scale";
import { toWindowScale } from "./window-scale";

function renderScale(from: string, to: string, evaluatedOn: string, releaseDays: string[], activeDay: string | null = null) {
  const scale = toWindowScale(from, to, evaluatedOn);
  if (scale === null) throw new Error("Expected a scale");
  return render(<ReleaseWindowScale activeDay={activeDay} releaseDays={releaseDays} scale={scale} />).container;
}

describe("release window scale", () => {
  it("is decorative: the heading and the cards state the window and the dates", () => {
    const scale = renderScale("2026-10-11", "2026-10-17", "2026-10-17", ["2026-10-14"]);

    expect(scale.firstElementChild).toHaveAttribute("aria-hidden", "true");
  });

  it("sets one fire needle per release day inside the window, never for a day outside it", () => {
    const scale = renderScale("2026-10-11", "2026-10-17", "2026-10-17", [
      "2026-10-14",
      "2026-10-14",
      "2026-10-16",
      "2026-10-20",
    ]);

    expect(scale.querySelectorAll(".window-scale-needle")).toHaveLength(2);
    expect(scale.querySelectorAll(".window-scale-tick")).toHaveLength(7);
    expect(scale.querySelector(".window-scale-numeral-today")).toHaveTextContent("hoy");
  });

  it("opens an upcoming window with today as a lit lead-in", () => {
    const scale = renderScale("2026-10-18", "2026-10-24", "2026-10-17", ["2026-10-21"]);

    expect(scale.querySelectorAll(".window-scale-tick")).toHaveLength(8);
    expect(scale.querySelectorAll(".window-scale-tick-elapsed")).toHaveLength(1);
    expect(scale.querySelector(".window-scale-numeral")).toHaveTextContent("hoy");
  });

  it("lifts the needle of the release a visitor points at", () => {
    const scale = renderScale("2026-10-11", "2026-10-17", "2026-10-17", ["2026-10-14", "2026-10-16"], "2026-10-16");

    expect(scale.querySelector(".window-scale")).toHaveClass("window-scale-pointing");
    expect(scale.querySelectorAll(".window-scale-needle-active")).toHaveLength(1);
  });
});
