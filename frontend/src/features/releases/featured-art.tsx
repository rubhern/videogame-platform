import { useState, type CSSProperties } from "react";

import type { FeaturedArt } from "./featured-releases-view-model";

type FeaturedArtFrameProps = {
  /** The image, then the steps a failed load falls back to, ending at the product fallback. */
  art: FeaturedArt[];
  scale: "hero" | "card";
};

/**
 * One landscape featured frame. A `fill` image is cropped to the frame; any other image is shown
 * whole over its own blurred light, so a portrait cover is presented on purpose and never stretched
 * or cropped. When a provider image fails to load, the frame steps down to the next source and
 * finally to the product-owned fallback, so the composition never breaks.
 */
export function FeaturedArtFrame({ art, scale }: FeaturedArtFrameProps) {
  const [step, setStep] = useState(0);
  const current = art[Math.min(step, art.length - 1)];
  if (current === undefined) {
    return null;
  }
  const source = scale === "hero" ? current.url : current.compactUrl;
  const light =
    current.presentation === "contain"
      ? ({ "--art": `url(${JSON.stringify(source)})` } as CSSProperties)
      : undefined;

  return (
    <div
      className={`featured-art featured-art-${current.presentation}`}
      data-art-kind={current.kind}
      style={light}
    >
      <img
        alt={current.alt}
        className="featured-art-image"
        decoding="async"
        loading={scale === "hero" ? "eager" : "lazy"}
        onError={() => setStep((value) => Math.min(value + 1, art.length - 1))}
        src={source}
      />
    </div>
  );
}
