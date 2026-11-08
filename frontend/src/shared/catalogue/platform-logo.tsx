import { useState, type CSSProperties } from "react";

import { platformLogoAssets, type PlatformLogoName } from "./platform-logo-assets";

type PlatformLogoProps = { name: PlatformLogoName; size?: "compact" | "large" };

/** Decorative artwork; the containing control, list or heading owns the product's full name. */
export function PlatformLogo({ name, size = "compact" }: PlatformLogoProps) {
  const asset = platformLogoAssets[name];
  const source = asset.file === null ? undefined : `/assets/platforms/${asset.file}`;
  const [failedSource, setFailedSource] = useState<string>();
  const className = `platform-logo app-select-icon app-select-icon-${name}`;
  let mark;

  if (source === undefined || failedSource === source) {
    // A known identity with unavailable artwork keeps its name, never another brand/device.
    mark = <span aria-hidden="true" className="platform-logo-text">{asset.label}</span>;
  } else if (asset.treatment === "original") {
    mark = (
      <img
        alt=""
        aria-hidden="true"
        className={className}
        onError={() => setFailedSource(source)}
        src={source}
      />
    );
  } else {
    const style: CSSProperties & { "--platform-logo-source": string } = {
      "--platform-logo-source": `url("${source}")`,
    };
    mark = <span aria-hidden="true" className={`${className} platform-logo-legacy`} style={style} />;
  }

  return size === "large" ? (
    <span aria-hidden="true" className={`platform-logo-stage platform-logo-stage-${name}`}>{mark}</span>
  ) : mark;
}
