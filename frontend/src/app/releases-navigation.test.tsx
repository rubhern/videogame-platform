import { act, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it } from "vitest";
import { render } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { ReleasesNavigation } from "./releases-navigation";

function Navigation() {
  return <><ReleasesNavigation /><h1>Outside</h1><button>Next control</button></>;
}
function renderNavigation(path: string) {
  const router = createMemoryRouter([{ path: "*", element: <Navigation /> }], { initialEntries: [path] });
  render(<RouterProvider router={router} />);
  return { router };
}

it("exposes exactly three ordered destinations with the active page and no TOPómetro", async () => {
  const user = userEvent.setup();
  renderNavigation("/lanzamientos/destacados");
  const nav = screen.getByRole("navigation", { name: "Principal" });
  const trigger = within(nav).getByRole("button", { name: "Lanzamientos" });
  expect(trigger).toHaveAttribute("aria-expanded", "false");
  expect(trigger).toHaveAttribute("aria-current", "true");
  expect(within(nav).queryByRole("link")).toBeNull();
  await user.click(trigger);
  const links = within(nav).getAllByRole("link");
  expect(links.map(link => link.textContent)).toEqual(["Destacados", "Explorar", "Calendario"]);
  expect(links.map(link => link.getAttribute("href"))).toEqual([
    "/lanzamientos/destacados", "/lanzamientos", "/lanzamientos/calendario",
  ]);
  expect(links[0]).toHaveAttribute("aria-current", "page");
  expect(within(nav).queryByText("TOPómetro")).toBeNull();
});

it("supports Enter, Space, Tab, Escape with focus return, blur and outside dismissal", async () => {
  const user = userEvent.setup();
  renderNavigation("/not-implemented");
  const trigger = screen.getByRole("button", { name: "Lanzamientos" });
  trigger.focus();
  await user.keyboard("{Enter}{Tab}");
  expect(screen.getByRole("link", { name: "Destacados" })).toHaveFocus();
  await user.keyboard("{Escape}");
  expect(trigger).toHaveFocus();
  expect(trigger).toHaveAttribute("aria-expanded", "false");
  await user.keyboard(" ");
  expect(trigger).toHaveAttribute("aria-expanded", "true");
  await user.keyboard("{Tab}{Tab}{Tab}{Tab}");
  expect(trigger).toHaveAttribute("aria-expanded", "false");
  await user.click(trigger);
  await user.click(screen.getByRole("heading", { name: "Outside" }));
  expect(trigger).toHaveAttribute("aria-expanded", "false");
});

it("closes after navigation, including history and a same-page selection, and preserves explorer filters", async () => {
  const user = userEvent.setup();
  const { router } = renderNavigation("/lanzamientos?sort=hype&platformIds=pc&page=2");
  const getTrigger = () => screen.getByRole("button", { name: "Lanzamientos" });
  await user.click(getTrigger());
  const explore = screen.getByRole("link", { name: "Explorar" });
  expect(explore).toHaveAttribute("href", "/lanzamientos?sort=hype&platformIds=pc");
  expect(explore).toHaveAttribute("aria-current", "page");
  await user.click(explore);
  expect(getTrigger()).toHaveAttribute("aria-expanded", "false");
  expect(getTrigger()).toHaveFocus();
  await user.click(getTrigger());
  await user.click(screen.getByRole("link", { name: "Explorar" }));
  expect(getTrigger()).toHaveAttribute("aria-expanded", "false");
  await user.click(getTrigger());
  await act(() => router.navigate("/not-implemented"));
  expect(getTrigger()).toHaveAttribute("aria-expanded", "false");
  expect(getTrigger()).not.toHaveAttribute("aria-current");
  await user.click(getTrigger());
  await act(() => router.navigate(-1));
  expect(getTrigger()).toHaveAttribute("aria-expanded", "false");
});
