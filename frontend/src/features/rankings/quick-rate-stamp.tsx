import type { MouseEventHandler, Ref } from "react";

import { thermalBand, thermalLabels } from "../../shared/score/thermal-band";
import { formatPersonalScore } from "../ratings/personal-score";
import type { ListPersonalRating } from "./use-ranking-personal-ratings";

/**
 * The player's own mark on a ranked game (#574): one round control that both shows and changes it.
 * Unrated, it is an open dial in ice inviting a score; rated, it holds the player's score in its own
 * temperature, so a glance down the ledger shows what is still to rate. It never reads like the
 * ranking's reading beside it: that one is the source's, this one is the player's.
 */
export function QuickRateStamp({
  title,
  state,
  onOpen,
  ref,
  variant,
}: {
  title: string;
  state: ListPersonalRating;
  onOpen: MouseEventHandler<HTMLButtonElement>;
  ref?: Ref<HTMLButtonElement>;
  variant: "podium" | "row";
}) {
  const rating = state.status === "known" ? state.rating : null;
  const value = rating?.contribution ?? null;
  const band = value === null ? null : thermalBand(value);
  const checking = state.status === "checking";
  const label =
    value !== null
      ? `Tu puntuación de ${title}: ${band ? `${thermalLabels[band]} ` : ""}${formatPersonalScore(value)}/10. Cambiar`
      : checking
        ? `Tu puntuación de ${title}: comprobando`
        : `Puntuar ${title}`;

  return (
    <button
      aria-haspopup="dialog"
      aria-label={label}
      className={`quick-rate-stamp quick-rate-stamp-${variant}`}
      data-state={value !== null ? "rated" : checking ? "checking" : "open"}
      data-thermal={band ?? undefined}
      onClick={onOpen}
      ref={ref}
      type="button"
    >
      <span aria-hidden="true" className="quick-rate-dial">
        {value !== null ? (
          // Keyed by value so a new score lands with its own small press.
          <strong key={value}>{formatPersonalScore(value)}</strong>
        ) : (
          <span className="quick-rate-plus" />
        )}
      </span>
      <span aria-hidden="true" className="quick-rate-caption">
        {value !== null ? "Tu puntuación" : "Puntuar"}
      </span>
    </button>
  );
}
