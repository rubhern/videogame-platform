import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { BrandMark, BrandWordmark } from "./brand-logo";

describe("brand logo", () => {
  it("stays decorative inside the link that names the brand", () => {
    const { container } = render(
      <a href="/" aria-label="Gameómetro · Inicio">
        <BrandMark />
        <BrandWordmark />
      </a>,
    );

    const svgs = container.querySelectorAll("svg");
    expect(svgs).toHaveLength(2);
    svgs.forEach((svg) => expect(svg).toHaveAttribute("aria-hidden", "true"));
    expect(container.querySelector("title")).toBeNull();
  });

  it("gives every instance its own gradient ids", () => {
    const { container } = render(
      <>
        <BrandMark />
        <BrandMark />
      </>,
    );

    const ids = [...container.querySelectorAll("[id]")].map((element) => element.id);
    expect(ids.length).toBeGreaterThan(0);
    expect(new Set(ids).size).toBe(ids.length);
    const paints = [...container.querySelectorAll("*")].flatMap((element) =>
      ["fill", "stroke"]
        .map((attribute) => element.getAttribute(attribute) ?? "")
        .filter((paint) => paint.startsWith("url(#")),
    );
    expect(paints.length).toBeGreaterThan(0);
    for (const paint of paints) expect(ids).toContain(paint.slice(5, -1));
  });
});
