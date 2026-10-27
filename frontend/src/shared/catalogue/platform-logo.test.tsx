import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { SelectIcon } from "../ui/select-icon";
import { PlatformLogo } from "./platform-logo";
import { PlatformMarks } from "./platform-marks";

describe("platform logos", () => {
  it("keeps the product name as the control's only accessible name", () => {
    render(<button type="button"><SelectIcon name="android" />Android</button>);
    const button = screen.getByRole("button", { name: "Android" });
    expect(within(button).getByAltText("")).toHaveAttribute("src", "/assets/platforms/android.svg");
    expect(screen.queryByRole("img")).not.toBeInTheDocument();

    fireEvent.error(within(button).getByAltText(""));

    expect(button).toHaveAccessibleName("Android");
    expect(button.querySelector("img")).toBeNull();
    expect(button).toHaveTextContent("Android");
    expect(button.querySelector(".app-select-icon-platform")).toBeNull();
  });

  it("can show a different original after an asset fails without reusing its failure", () => {
    const { rerender } = render(<PlatformLogo name="android" size="large" />);
    fireEvent.error(screen.getByAltText(""));
    rerender(<PlatformLogo name="linux" size="large" />);
    expect(screen.getByAltText("")).toHaveAttribute("src", "/assets/platforms/linux.svg");
  });

  it("uses the modern Windows original without an edition wordmark and preserves its name on failure", () => {
    render(<button type="button"><SelectIcon name="windows" />Windows PC</button>);
    const button = screen.getByRole("button", { name: "Windows PC" });
    const image = within(button).getByAltText("");
    expect(image).toHaveAttribute("src", "/assets/platforms/windows.svg");
    fireEvent.error(image);
    expect(button).toHaveAccessibleName("Windows PC");
    expect(button.querySelector(".platform-logo-text")).toHaveTextContent("Windows");
    expect(button).not.toHaveTextContent("11");
  });

  it("identifies SteamVR by its own name, without presenting the Steam symbol", () => {
    render(<button type="button"><SelectIcon name="steamvr" />SteamVR</button>);
    const button = screen.getByRole("button", { name: "SteamVR" });
    expect(button.querySelector("img, .platform-logo-legacy")).toBeNull();
    expect(button.querySelector(".platform-logo-text")).toHaveTextContent("SteamVR");
  });

  it("preserves known platform names and the named unknown-platform fallback in card lists", () => {
    render(<PlatformMarks label="Plataformas del juego" marks={[
      { key: "windows-id", name: "Windows PC", icon: "windows" },
      { key: "mac-id", name: "macOS", icon: "macos" },
      { key: "steamvr-id", name: "SteamVR", icon: "steamvr" },
      { key: "new-id", name: "Nueva plataforma", icon: "platform" },
    ]} />);
    const list = screen.getByRole("list", { name: "Plataformas del juego" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(4);
    expect(list).toHaveTextContent("Windows PC");
    expect(within(list).getByText("Windows PC")).toBeVisible();
    expect(list).toHaveTextContent("macOS");
    expect(within(list).getByText("Nueva plataforma")).toBeVisible();
    expect(list.querySelectorAll("svg")).toHaveLength(1);
  });
});
