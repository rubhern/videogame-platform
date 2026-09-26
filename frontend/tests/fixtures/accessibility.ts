import AxeBuilder from "@axe-core/playwright";
import type { Page } from "@playwright/test";

/**
 * Runs axe once finite entrance animations have finished, so contrast is measured on settled
 * text rather than mid fade-in. Looping ambient layers (mist, motes) are excluded: they never
 * finish and carry no text.
 */
export async function analyzeAccessibility(page: Page) {
  await page.evaluate(async () => {
    await document.fonts.ready;
    const nextFrame = () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    // Late-rendered content can start its entrance after the first wait, so settle until quiet.
    for (;;) {
      await nextFrame();
      const running = document
        .getAnimations()
        .filter((animation) => animation.playState === "running")
        .filter((animation) => Number.isFinite(animation.effect?.getComputedTiming().endTime ?? Infinity));
      if (running.length === 0) return;
      await Promise.all(running.map((animation) => animation.finished.catch(() => undefined)));
    }
  });
  return new AxeBuilder({ page }).analyze();
}
