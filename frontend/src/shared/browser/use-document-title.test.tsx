import { render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { useDocumentTitle } from "./use-document-title";

function Titled({ title }: { title: string | null }) {
  useDocumentTitle(title);
  return null;
}

describe("useDocumentTitle", () => {
  afterEach(() => {
    document.title = "";
  });

  it("names the tab while shown and restores the previous title afterwards", () => {
    document.title = "Gameómetro";
    const view = render(<Titled title="Lanzamientos · Gameómetro" />);
    expect(document.title).toBe("Lanzamientos · Gameómetro");

    view.rerender(<Titled title="Calendario de lanzamientos · Gameómetro" />);
    expect(document.title).toBe("Calendario de lanzamientos · Gameómetro");

    view.unmount();
    expect(document.title).toBe("Gameómetro");
  });

  it("leaves the current title alone while there is nothing to name", () => {
    document.title = "Gameómetro";
    render(<Titled title={null} />);
    expect(document.title).toBe("Gameómetro");
  });
});
