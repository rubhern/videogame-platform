import { useId } from "react";

import markSource from "./gameometro-mark.svg?raw";
import wordmarkSource from "./gameometro-wordmark.svg?raw";

/**
 * Inlines a canonical brand file so CSS can light its parts (the needle reacts to the brand
 * link). The files are product-owned and bundled at build time, never derived from input, so
 * their markup is trusted. Each instance gets its own gradient ids, and the standalone title
 * is dropped because the surrounding link already names the brand.
 */
function useInlineBrandSvg(source: string) {
  const prefix = `gm${useId().replace(/[^\w-]/g, "")}`;
  return {
    __html: source
      .replace(/<title>[^<]*<\/title>/, "")
      .replace(/ role="img" aria-label="[^"]*"/, ' aria-hidden="true" focusable="false"')
      .replaceAll("gmx-", `${prefix}-`),
  };
}

/** The compact Tilde mark: a G whose gauge needle leaves through its mouth as an accent. */
export function BrandMark({ className }: { className?: string }) {
  return <span className={className} dangerouslySetInnerHTML={useInlineBrandSvg(markSource)} />;
}

/** The Gameómetro wordmark, whose ó carries the same needle as its accent. */
export function BrandWordmark({ className }: { className?: string }) {
  return <span className={className} dangerouslySetInnerHTML={useInlineBrandSvg(wordmarkSource)} />;
}
