import type { components } from "../api/generated/schema";

/** A recognition cue shared by release, search and personal-rating listings. */
export function GameGenres({ genres }: { genres: components["schemas"]["Genre"][] | undefined }) {
  const labels = (genres ?? []).slice(0, 2).map(genre => genre.name).join(" · ");
  return labels ? <p aria-label={`Géneros: ${labels}`} className="card-genres" title={labels}>{labels}</p> : null;
}
