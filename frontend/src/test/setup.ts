import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";

// JSDOM has no layout/scroll engine; Playwright owns actual restoration assertions.
window.scrollTo = vi.fn();

afterEach(() => {
  cleanup();
});
