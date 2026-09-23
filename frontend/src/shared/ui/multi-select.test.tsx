import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";

import { MultiSelect, type MultiSelectOption } from "./multi-select";

const options: MultiSelectOption[] = [
  { value: "ps5", label: "PlayStation 5", icon: "playstation" },
  { value: "switch2", label: "Nintendo Switch 2", icon: "nintendo-switch-2" },
  { value: "win", label: "Windows PC", icon: "windows" },
];

function Harness({ initial = [] as string[] }) {
  const [selected, setSelected] = useState<string[]>(initial);
  return (
    <div>
      <button type="button">Fuera</button>
      <MultiSelect
        allLabel="Todas"
        icon="platform"
        label="Plataforma"
        onToggle={(value) =>
          setSelected((current) =>
            current.includes(value)
              ? current.filter((existing) => existing !== value)
              : [...current, value],
          )
        }
        options={options}
        selected={selected}
      />
    </div>
  );
}

function trigger() {
  return screen.getByRole("combobox", { name: /Plataforma/ });
}

describe("MultiSelect", () => {
  it("summarises no selection as the all-label and stays closed", () => {
    render(<Harness />);

    expect(trigger()).toHaveTextContent("Todas");
    expect(trigger()).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });

  it("opens a multi-selectable listbox of options on click", async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await user.click(trigger());

    expect(trigger()).toHaveAttribute("aria-expanded", "true");
    const listbox = screen.getByRole("listbox", { name: "Plataforma" });
    expect(listbox).toHaveAttribute("aria-multiselectable", "true");
    const items = screen.getAllByRole("option");
    expect(items).toHaveLength(3);
    expect(items.every((item) => item.getAttribute("aria-selected") === "false")).toBe(true);
  });

  it("selects several values without closing and summarises the count", async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await user.click(trigger());
    await user.click(screen.getByRole("option", { name: "PlayStation 5" }));

    expect(screen.getByRole("listbox")).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "PlayStation 5" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(trigger()).toHaveTextContent("PlayStation 5");

    await user.click(screen.getByRole("option", { name: "Windows PC" }));
    expect(screen.getByRole("listbox")).toBeInTheDocument();
    expect(trigger()).toHaveTextContent("2 seleccionadas");
  });

  it("clears back to the all-label when the last value is unselected", async () => {
    const user = userEvent.setup();
    render(<Harness initial={["ps5"]} />);

    expect(trigger()).toHaveTextContent("PlayStation 5");
    await user.click(trigger());
    await user.click(screen.getByRole("option", { name: "PlayStation 5" }));

    expect(screen.getByRole("option", { name: "PlayStation 5" })).toHaveAttribute(
      "aria-selected",
      "false",
    );
    expect(trigger()).toHaveTextContent("Todas");
  });

  it("closes on Escape and returns focus to the trigger", async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await user.click(trigger());
    expect(screen.getByRole("listbox")).toBeInTheDocument();

    await user.keyboard("{Escape}");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    expect(trigger()).toHaveFocus();
  });

  it("closes when the pointer goes outside the control", async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await user.click(trigger());
    expect(screen.getByRole("listbox")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Fuera" }));
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });

  it("navigates with the keyboard and toggles with Space and Enter", async () => {
    const user = userEvent.setup();
    render(<Harness />);

    trigger().focus();
    await user.keyboard("{ArrowDown}"); // opens, active on the first option
    expect(trigger()).toHaveAttribute("aria-expanded", "true");
    await user.keyboard("{ArrowDown}"); // moves to the second option
    expect(screen.getByRole("option", { name: "Nintendo Switch 2" })).toHaveAttribute(
      "data-active",
      "true",
    );

    await user.keyboard(" ");
    expect(screen.getByRole("option", { name: "Nintendo Switch 2" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(trigger()).toHaveTextContent("Nintendo Switch 2");

    await user.keyboard("{Enter}");
    expect(screen.getByRole("option", { name: "Nintendo Switch 2" })).toHaveAttribute(
      "aria-selected",
      "false",
    );
    expect(trigger()).toHaveTextContent("Todas");
  });
});
