import type { CSSProperties } from "react";

/** A page's opening is lit by at most this many of the games it presents. */
const MAX_LIGHTS = 3;

/**
 * The night stage behind a page's opening, lit by the art the page already shows: each image's
 * own colour, blown up, blurred and capped in brightness, falling away into night. It adds no
 * request beyond images the page displays, never stores or transforms them, is hidden from
 * assistive technology, takes no input and never carries readable content. Without images the
 * stage is plain night.
 */
export function StageLight({ images }: { images: readonly string[] }) {
  const lights = images.slice(0, MAX_LIGHTS);
  return (
    <div aria-hidden="true" className="stage-light" data-lights={lights.length}>
      {lights.map((url, index) => (
        <span
          className="stage-light-source"
          key={`${index}-${url}`}
          style={{ "--art": `url(${JSON.stringify(url)})` } as CSSProperties}
        />
      ))}
    </div>
  );
}
