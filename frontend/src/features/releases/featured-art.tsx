import { useState, type CSSProperties } from "react";

import type { FeaturedArt } from "./featured-releases-view-model";

type FeaturedArtFrameProps = {
  /** The image, then the steps a failed load falls back to, ending at the product fallback. */
  art: FeaturedArt[];
  scale: "hero" | "card";
};

/**
 * From this width-to-height ratio a loaded image is a panoramic banner. Provider banners place
 * their subject anywhere across a 3:1 strip, so the hero shows most of their width instead of a
 * landscape crop that keeps only the middle.
 */
const PANORAMA_RATIO = 2.2;

/**
 * One landscape featured frame. A `fill` image is cropped to the frame; any other image is shown
 * whole over its own blurred light, so a portrait cover is presented on purpose and never stretched
 * or cropped. A loaded image's own ratio marks a panoramic banner for the hero's wider framing. When a provider image fails to load, the frame steps down to the next source and
 * finally to the product-owned fallback, so the composition never breaks.
 */
export function FeaturedArtFrame({ art, scale }: FeaturedArtFrameProps) {
  const [step, setStep] = useState(0);
  const [panorama, setPanorama] = useState(false);
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
      data-art-shape={panorama ? "panorama" : undefined}
      style={light}
    >
      <img
        alt={current.alt}
        className="featured-art-image"
        decoding="async"
        loading={scale === "hero" ? "eager" : "lazy"}
        onError={() => {
          setPanorama(false);
          setStep((value) => Math.min(value + 1, art.length - 1));
        }}
        onLoad={(event) => {
          const { naturalWidth, naturalHeight } = event.currentTarget;
          setPanorama(naturalHeight > 0 && naturalWidth / naturalHeight >= PANORAMA_RATIO);
        }}
        src={source}
      />
    </div>
  );
}
