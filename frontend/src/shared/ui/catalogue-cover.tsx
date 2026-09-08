import { useState } from "react";
import { Link } from "react-router-dom";

type CatalogueCoverProps = {
  cover: {
    url: string;
    alternativeText: string;
  } & (
    | { kind: "provider"; attribution: { label: string; sourceUrl: string } }
    | { kind: "local-preview"; attribution: { label: string; sourceUrl: string } }
    | { kind: "fallback" }
  );
  to?: string;
};

export function CatalogueCover({ cover, to }: CatalogueCoverProps) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const hasImageAttribution = cover.kind !== "fallback";
  const failed = hasImageAttribution && failedUrl === cover.url;
  const attribution = hasImageAttribution && !failed ? cover.attribution : null;

  const image = (
    <img
      alt={failed ? "Carátula oficial no disponible" : cover.alternativeText}
      className="catalogue-cover"
      height={400}
      loading="lazy"
      onError={() => {
        if (hasImageAttribution) {
          setFailedUrl(cover.url);
        }
      }}
      src={failed ? "/assets/covers/fallback.svg" : cover.url}
      width={300}
    />
  );

  return (
    <figure className="cover-figure">
      {to === undefined ? (
        <div className="cover-link cover-static">{image}</div>
      ) : (
        <Link className="cover-link" to={to} tabIndex={-1}>
          {image}
        </Link>
      )}
      <figcaption className="cover-caption">
        {attribution === null ? (
          "Carátula oficial no disponible"
        ) : (
          <>
            Carátula:{" "}
            <a className="text-link" href={attribution.sourceUrl} rel="noreferrer">
              {attribution.label}
            </a>
          </>
        )}
      </figcaption>
    </figure>
  );
}
