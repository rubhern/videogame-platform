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

it("exposes Lanzamientos, Lo mejor and Plataformas as three disclosures in that order", async () => {
  const user = userEvent.setup();
  renderNavigation("/lanzamientos/destacados");
  const nav = screen.getByRole("navigation", { name: "Principal" });
  const sections = within(nav).getAllByRole("button");
  expect(sections.map(section => section.textContent?.trim())).toEqual(["Lanzamientos", "Lo mejor", "Plataformas"]);
  for (const section of sections) expect(section).toHaveAttribute("aria-expanded", "false");
  const releases = within(nav).getByRole("button", { name: "Lanzamientos" });
  const best = within(nav).getByRole("button", { name: "Lo mejor" });
  expect(releases).toHaveAttribute("aria-current", "true");
  expect(best).not.toHaveAttribute("aria-current");
  // Every destination waits in its panel.
  expect(within(nav).queryAllByRole("link")).toEqual([]);

  await user.click(releases);
  const links = within(nav).getAllByRole("link");
  expect(links.map(link => link.textContent)).toEqual(["Destacados", "Explorar", "Calendario"]);
  expect(links.map(link => link.getAttribute("href"))).toEqual([
    "/lanzamientos/destacados", "/lanzamientos", "/lanzamientos/calendario",
  ]);
  expect(links[0]).toHaveAttribute("aria-current", "page");

  // Lo mejor holds TOPómetro, named by its lockup; opening it closes Lanzamientos.
  await user.click(best);
  expect(releases).toHaveAttribute("aria-expanded", "false");
  expect(best).toHaveAttribute("aria-expanded", "true");
  const topometro = within(nav).getByRole("link", { name: "TOPómetro" });
  expect(within(nav).getAllByRole("link")).toEqual([topometro]);
  expect(topometro).toHaveAttribute("href", "/topometro");
  expect(topometro).not.toHaveAttribute("aria-current");
});

it.each([
  ["/topometro?source=gameometro", "Lo mejor", "TOPómetro"],
  ["/plataformas", "Plataformas", "Ver todas las plataformas"],
])("marks %s current in its own section only", async (path, section, destination) => {
  const user = userEvent.setup();
  renderNavigation(path);
  const nav = screen.getByRole("navigation", { name: "Principal" });
  const trigger = within(nav).getByRole("button", { name: section });
  expect(trigger).toHaveAttribute("aria-current", section === "Lo mejor" ? "true" : "page");
  expect(within(nav).getAllByRole("button").filter(button => button.hasAttribute("aria-current"))).toEqual([trigger]);
  await user.click(trigger);
  expect(within(nav).getByRole("link", { name: destination })).toHaveAttribute("aria-current", "page");
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
  const { router } = renderNavigation("/lanzamientos?sort=releaseDate&platformIds=pc&page=2");
  const getTrigger = () => screen.getByRole("button", { name: "Lanzamientos" });
  await user.click(getTrigger());
  const explore = screen.getByRole("link", { name: "Explorar" });
  expect(explore).toHaveAttribute("href", "/lanzamientos?sort=releaseDate&platformIds=pc");
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

it("offers only the four direct platform catalogues and the index, with keyboard dismissal", async () => {
  const user = userEvent.setup();
  const { router } = renderNavigation("/plataformas/playstation-5/topometro");
  const trigger = screen.getByRole("button", { name: "Plataformas" });
  expect(trigger).toHaveAttribute("aria-current", "true");
  trigger.focus();
  await user.keyboard("{Enter}{Tab}");
  const first = screen.getByRole("link", { name: "Nintendo Switch 2" });
  expect(first).toHaveFocus();
  expect(first).toHaveAttribute("href", "/plataformas/nintendo-switch-2/catalogo");
  expect(screen.getByRole("link", { name: "PlayStation 5" })).toHaveAttribute("href", "/plataformas/playstation-5/catalogo");
  expect(screen.getByRole("link", { name: "Xbox Series X|S" })).toHaveAttribute("href", "/plataformas/xbox-series-x-s/catalogo");
  expect(screen.getByRole("link", { name: "Windows PC" })).toHaveAttribute("href", "/plataformas/windows-pc/catalogo");
  expect(screen.getByRole("link", { name: "Ver todas las plataformas" })).toHaveAttribute("href", "/plataformas");
  await user.keyboard("{Escape}");
  expect(trigger).toHaveFocus();
  expect(trigger).toHaveAttribute("aria-expanded", "false");
  await user.click(trigger);
  await user.click(screen.getByRole("heading", { name: "Outside" }));
  expect(trigger).toHaveAttribute("aria-expanded", "false");
  await user.click(trigger);
  await user.click(screen.getByRole("link", { name: "Windows PC" }));
  expect(router.state.location.pathname).toBe("/plataformas/windows-pc/catalogo");
  expect(trigger).toHaveAttribute("aria-expanded", "false");
  await act(() => router.navigate(-1));
  expect(trigger).toHaveAttribute("aria-expanded", "false");
});
