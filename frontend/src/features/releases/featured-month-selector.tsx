import { Link } from "react-router-dom";

import { CalendarIcon, ChevronIcon } from "./featured-icons";
import { adjacentMonths, formatMonthLabel, formatMonthPhrase } from "./featured-releases-view-model";
import { featuredSearchPath } from "./featured-search";

type FeaturedMonthSelectorProps = {
  /** The represented month, or null while the current month is still unknown. */
  month: string | null;
  /**
   * The evaluated month from the API's trusted date: stepping back to it returns to the landing
   * route itself, and its calendar year bounds the steps. Null until a response provides it.
   */
  currentMonth: string | null;
};

function MonthStep({
  direction,
  target,
  currentMonth,
}: {
  direction: "previous" | "next";
  target: string | null;
  currentMonth: string;
}) {
  const word = direction === "previous" ? "Mes anterior" : "Mes siguiente";
  if (target === null) {
    // January and December end the current year, the only one featured discovery presents. The
    // step stays in place as a disabled link: dimmed, named, announced as unavailable and never
    // focused.
    return (
      <span
        aria-disabled="true"
        aria-label={word}
        className="featured-month-step featured-month-step-disabled"
        role="link"
      >
        <ChevronIcon direction={direction} />
      </span>
    );
  }
  return (
    <Link
      aria-label={`${word}: ${formatMonthPhrase(target)}`}
      className="featured-month-step"
      to={featuredSearchPath(target === currentMonth ? null : target)}
    >
      <ChevronIcon direction={direction} />
    </Link>
  );
}

/**
 * The month the selection represents, between links to the adjacent months of the current
 * calendar year. Each step is a navigation, so the month lives in the URL and the browser
 * history. Until a response states the trusted current month, nothing says which months may be
 * offered, so the pill waits as a placeholder.
 */
export function FeaturedMonthSelector({ month, currentMonth }: FeaturedMonthSelectorProps) {
  if (month === null || currentMonth === null) {
    return <span aria-hidden="true" className="featured-month featured-month-skeleton" />;
  }
  const { previous, next } = adjacentMonths(month, currentMonth);
  return (
    <nav aria-label="Mes de la selección" className="featured-month">
      <MonthStep currentMonth={currentMonth} direction="previous" target={previous} />
      <p className="featured-month-label">
        <CalendarIcon />
        <time dateTime={month}>{formatMonthLabel(month)}</time>
      </p>
      <MonthStep currentMonth={currentMonth} direction="next" target={next} />
    </nav>
  );
}
