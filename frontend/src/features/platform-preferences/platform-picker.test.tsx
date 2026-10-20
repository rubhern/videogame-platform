import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";

import { toggleFilterValue } from "../releases/releases-search";
import { PlatformPicker } from "./platform-picker";
import { selectionSummary } from "./selection-summary";

const platforms = [
  { platformId: "10000000-0000-4000-8000-000000000002", name: "Nintendo Switch 2" },
  { platformId: "10000000-0000-4000-8000-000000000001", name: "PlayStation 5" },
  { platformId: "atari", name: "Atari 2600" },
  { platformId: "dreamcast", name: "Dreamcast" },
];

function Picker({ initial = [] as string[] }) {
  const [selected, setSelected] = useState(initial);
  return (
    <PlatformPicker
      legend="Tus plataformas"
      onToggle={(id) => setSelected((current) => toggleFilterValue(current, id))}
      platforms={platforms}
      selected={selected}
    />
  );
}

describe("platform picker", () => {
  it("is one named group of native checkboxes operable by keyboard", async () => {
    const user = userEvent.setup();
    render(<Picker />);

    expect(screen.getByRole("group", { name: "Tus plataformas" })).toBeInTheDocument();
    const ps5 = screen.getByRole("checkbox", { name: "PlayStation 5" });
    expect(ps5).not.toBeChecked();

    await user.tab();
    await user.tab();
    expect(ps5).toHaveFocus();
    await user.keyboard(" ");
    expect(ps5).toBeChecked();
    await user.click(screen.getByText("Nintendo Switch 2"));
    expect(screen.getByRole("checkbox", { name: "Nintendo Switch 2" })).toBeChecked();
  });

  it("offers only platforms with a recognized mark", () => {
    render(<Picker />);

    expect(screen.getAllByRole("checkbox")).toHaveLength(2);
    expect(screen.queryByRole("checkbox", { name: "Dreamcast" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /plataformas más/ })).not.toBeInTheDocument();
  });

  it("states the choice in words", () => {
    expect(selectionSummary(0)).toBe("Ninguna plataforma elegida");
    expect(selectionSummary(1)).toBe("1 plataforma elegida");
    expect(selectionSummary(3)).toBe("3 plataformas elegidas");
  });
});
