import { Link } from "react-router-dom";

import { CalendarIcon, ChevronIcon } from "./featured-icons";
import {
  formatRequestedPeriodLabel,
  formatRequestedPeriodPhrase,
  type FeaturedPeriodStep,
  type FeaturedReleasesViewModel,
} from "./featured-releases-view-model";
import {
  featuredSearchPath,
  type FeaturedGranularity,
  type FeaturedSearch,
} from "./featured-search";

type FeaturedPeriodSelectorProps = {
  /** The period the URL requests. */
  search: FeaturedSearch;
  /**
   * The response that represents the requested period, or null while it is still loading. Only a
   * response states which neighbouring periods the API offers and whether today lies inside.
   */
  model: FeaturedReleasesViewModel | null;
};

const granularities: { value: FeaturedGranularity; label: string }[] = [
  { value: "week", label: "Semana" },
  { value: "month", label: "Mes" },
];

const words = {
  week: { previous: "Semana anterior", next: "Semana siguiente", current: "Semana actual" },
  month: { previous: "Mes anterior", next: "Mes siguiente", current: "Mes actual" },
} as const;

function PeriodStep({
  granularity,
  direction,
  step,
}: {
  granularity: FeaturedGranularity;
  direction: "previous" | "next";
  /** The adjacent period, null at a navigation bound, undefined while the bounds are unknown. */
  step: FeaturedPeriodStep | null | undefined;
}) {
  const word = words[granularity][direction];
  if (step === null || step === undefined) {
    // A bound, or a period still loading: the step stays in place as a disabled link, dimmed,
    // named, announced as unavailable and never focused.
    return (
      <span
        aria-disabled="true"
        aria-label={word}
        className="featured-period-step featured-period-step-disabled"
        role="link"
      >
        <ChevronIcon direction={direction} />
      </span>
    );
  }
  return (
    <Link
      preventScrollReset
      aria-label={`${word}: ${step.phrase}`}
      className="featured-period-step"
      to={featuredSearchPath({ granularity, period: step.period })}
    >
      <ChevronIcon direction={direction} />
    </Link>
  );
}

/**
 * The period the selection represents, set as the instrument's readout: the Semana/Mes switch,
 * the steps to the adjacent periods around the period itself, and a way back to the current
 * period while another one is shown. Every choice is a navigation, so the period lives in the URL
 * and the browser history; the API alone states the bounds, so the steps wait for its response.
 */
export function FeaturedPeriodSelector({ search, model }: FeaturedPeriodSelectorProps) {
  const { granularity } = search;
  const label =
    model?.periodLabel ??
    (search.period === null ? null : formatRequestedPeriodLabel(granularity, search.period));
  const period = model?.period ?? search.period;
  // A week's compact range is read in full; a month's label already is.
  const phrase =
    model?.periodPhrase ??
    (search.period === null ? null : formatRequestedPeriodPhrase(granularity, search.period));
  const spoken = granularity === "week" && phrase !== null ? phrase.replace(/^la semana/u, "Semana") : null;
  const showsCurrentLink = model === null ? search.period !== null : !model.isCurrent;

  return (
    <div className="featured-period">
      <nav aria-label="Periodo de los destacados" className="featured-granularity">
        <ul>
          {granularities.map((option) => (
            <li key={option.value}>
              <Link
                preventScrollReset
                aria-current={option.value === granularity ? "true" : undefined}
                className="featured-granularity-option"
                to={featuredSearchPath({ granularity: option.value, period: null })}
              >
                {option.label}
              </Link>
            </li>
          ))}
        </ul>
      </nav>
      {label === null || period === null ? (
        <span aria-hidden="true" className="featured-period-stepper featured-period-skeleton" />
      ) : (
        <nav
          aria-label={granularity === "week" ? "Semana de la selección" : "Mes de la selección"}
          className="featured-period-stepper"
        >
          <PeriodStep direction="previous" granularity={granularity} step={model?.previous} />
          <p className="featured-period-label">
            <CalendarIcon />
            <time dateTime={period}>
              {spoken === null ? (
                label
              ) : (
                <>
                  <span aria-hidden="true">{label}</span>
                  <span className="sr-only">{spoken}</span>
                </>
              )}
            </time>
          </p>
          <PeriodStep direction="next" granularity={granularity} step={model?.next} />
        </nav>
      )}
      {showsCurrentLink ? (
        <Link
          preventScrollReset
          className="featured-period-current"
          to={featuredSearchPath({ granularity, period: null })}
        >
          {words[granularity].current}
        </Link>
      ) : null}
    </div>
  );
}
