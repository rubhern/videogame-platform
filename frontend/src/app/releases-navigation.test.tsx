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

it("exposes the three release destinations in the disclosure, then TOPómetro and Plataformas", async () => {
  const user = userEvent.setup();
  renderNavigation("/lanzamientos/destacados");
  const nav = screen.getByRole("navigation", { name: "Principal" });
  const trigger = within(nav).getByRole("button", { name: "Lanzamientos" });
  expect(trigger).toHaveAttribute("aria-expanded", "false");
  expect(trigger).toHaveAttribute("aria-current", "true");
  const topometro = within(nav).getByRole("link", { name: "TOPómetro" });
  expect(topometro).toHaveAttribute("href", "/topometro");
  expect(topometro).not.toHaveAttribute("aria-current");
  const platforms = within(nav).getByRole("button", { name: "Plataformas" });
  expect(platforms).toHaveAttribute("aria-expanded", "false");
  expect(within(nav).getAllByRole("link")).toEqual([topometro]);
  await user.click(trigger);
  const links = within(nav).getAllByRole("link").filter(link => link !== topometro && link !== platforms);
  expect(links.map(link => link.textContent)).toEqual(["Destacados", "Explorar", "Calendario"]);
  expect(links.map(link => link.getAttribute("href"))).toEqual([
    "/lanzamientos/destacados", "/lanzamientos", "/lanzamientos/calendario",
  ]);
  expect(links[0]).toHaveAttribute("aria-current", "page");
});

it.each([["/topometro?source=gameometro", "TOPómetro"], ["/plataformas", "Plataformas"]])(
  "marks %s as the current page without making Lanzamientos current", (path, name) => {
    renderNavigation(path);
    const nav = screen.getByRole("navigation", { name: "Principal" });
    expect(within(nav).getByRole(name === "Plataformas" ? "button" : "link", { name })).toHaveAttribute("aria-current", "page");
    expect([...within(nav).getAllByRole("link"), ...within(nav).getAllByRole("button")].filter(link => link.hasAttribute("aria-current"))).toHaveLength(1);
    expect(within(nav).getByRole("button", { name: "Lanzamientos" })).not.toHaveAttribute("aria-current");
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
