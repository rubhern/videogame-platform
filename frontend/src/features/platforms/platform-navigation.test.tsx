import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider, useLocation } from "react-router-dom";
import { expect, it } from "vitest";

import { platformCataloguePath } from "../platform-catalogue/platform-catalogue-search";
import { featuredPlatforms, type PlatformContext } from "./platform-context";
import { PlatformNavigation } from "./platform-navigation";

it.each<PlatformContext>([
  ...featuredPlatforms,
  { key: "android-id", name: "Android", logo: "android", platformIds: ["android-id"] },
])("keeps $name across destinations and browser history", async context => {
  function View() {
    const { pathname } = useLocation();
    const active = pathname.endsWith("catalogo") ? "catalogue" : pathname.endsWith("topometro") ? "topometro" : "upcoming";
    return <PlatformNavigation active={active} context={context} others={[]} pathForPlatform={platform => platformCataloguePath(platform.key)} />;
  }
  const router = createMemoryRouter([{ path: "*", element: <View /> }], { initialEntries: [`${platformCataloguePath(context.key)}?releaseState=upcoming&page=2`] });
  render(<RouterProvider router={router} />);
  const nav = screen.getByRole("navigation", { name: `Navegación de ${context.name}` });
  const catalogue = within(nav).getByRole("link", { name: "Catálogo" });
  const ranking = within(nav).getByRole("link", { name: "TOPómetro" });
  const upcoming = within(nav).getByRole("link", { name: "Próximos destacados" });
  expect(catalogue).toHaveAttribute("aria-current", "page");
  expect(catalogue).toHaveAttribute("href", `${platformCataloguePath(context.key)}?releaseState=upcoming&page=2`);
  const user = userEvent.setup();
  await user.click(ranking);
  expect(router.state.location.pathname).toBe(`/plataformas/${context.key}/topometro`);
  expect(ranking).toHaveAttribute("aria-current", "page");
  await user.click(upcoming);
  expect(router.state.location.pathname).toBe(`/plataformas/${context.key}/proximos`);
  expect(upcoming).toHaveAttribute("aria-current", "page");
  await act(() => router.navigate(-1));
  expect(ranking).toHaveAttribute("aria-current", "page");
  await act(() => router.navigate(-1));
  expect(catalogue).toHaveAttribute("aria-current", "page");
  await act(() => router.navigate(1));
  expect(ranking).toHaveAttribute("aria-current", "page");
});
