import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";

import { AppSelect } from "./app-select";

const options = [
  { value: "", label: "Todas", icon: "platform" as const },
  { value: "switch", label: "Nintendo Switch 2", icon: "nintendo-switch" as const },
  { value: "ps5", label: "PlayStation 5", icon: "playstation" as const },
  { value: "windows", label: "Windows PC", icon: "windows" as const },
  { value: "xbox", label: "Xbox Series X|S", icon: "xbox" as const },
];

function Example({ onChange = vi.fn() }: { onChange?: (value: string) => void }) {
  const [value, setValue] = useState("");
  return (
    <>
      <AppSelect
        icon="platform"
        inlineLabel
        label="Plataforma:"
        onChange={(next) => { setValue(next); onChange(next); }}
        options={options}
        value={value}
      />
      <button type="button">Fuera</button>
    </>
  );
}

describe("app select", () => {
  it("opens from the entire trigger and shows the supplied platform marks", async () => {
    const user = userEvent.setup();
    render(<Example />);

    await user.click(screen.getByText("Plataforma:"));
    const combo = screen.getByRole("combobox", { name: "Plataforma: Todas" });
    expect(combo).toHaveAttribute("aria-expanded", "true");
    const list = within(screen.getByRole("listbox", { name: "Plataforma:" }));
    for (const [name, mark] of [
      ["Nintendo Switch 2", "nintendo-switch"],
      ["PlayStation 5", "playstation"],
      ["Windows PC", "windows"],
      ["Xbox Series X|S", "xbox"],
    ] as const) {
      expect(list.getByRole("option", { name }).querySelector(`.app-select-icon-${mark}`)).toBeInTheDocument();
    }

    await user.click(list.getByRole("option", { name: "Windows PC" }));
    expect(screen.getByRole("combobox", { name: "Plataforma: Windows PC" })).toHaveAttribute("aria-expanded", "false");
    expect(screen.getByRole("combobox", { name: "Plataforma: Windows PC" })).toHaveFocus();

    await user.click(screen.getByRole("combobox", { name: "Plataforma: Windows PC" }));
    await user.click(screen.getByRole("button", { name: "Fuera" }));
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });

  it("supports arrows, Home, End, selection, Escape and Tab", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<Example onChange={onChange} />);
    const combo = screen.getByRole("combobox", { name: "Plataforma: Todas" });
    combo.focus();

    await user.keyboard("{ArrowDown}{ArrowDown}{Enter}");
    expect(onChange).toHaveBeenLastCalledWith("switch");
    expect(combo).toHaveFocus();

    await user.keyboard("{ArrowDown}{End}{Escape}");
    expect(combo).toHaveAttribute("aria-expanded", "false");
    expect(onChange).toHaveBeenCalledTimes(1);

    await user.keyboard("{ArrowDown}{Home}{ArrowDown}{ArrowDown}{Enter}");
    expect(onChange).toHaveBeenLastCalledWith("ps5");

    await user.keyboard("{ArrowDown}{Tab}");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Fuera" })).toHaveFocus();
  });
});
