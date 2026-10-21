import { Link } from "react-router-dom";

import { ChevronIcon } from "./featured-icons";
import {
  formatMonth,
  formatShortMonth,
  monthWindow,
  type ReleaseCalendarModel,
} from "./release-calendar-model";

type ReleaseCalendarMonthsProps = {
  /** The served month, or null while the first read is under way. */
  model: ReleaseCalendarModel | null;
  pathFor: (month: string) => string;
};

function Step({
  direction,
  month,
  pathFor,
}: {
  direction: "previous" | "next";
  /** The adjacent month, null at a bound or while the bounds are unknown. */
  month: string | null;
  pathFor: (month: string) => string;
}) {
  const word = direction === "previous" ? "Mes anterior" : "Mes siguiente";
  if (month === null) {
    // The current month has no earlier one to show, and the forward bound no later one: the step
    // stays in place, dimmed, announced as unavailable and never focused.
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
      aria-label={`${word}: ${formatMonth(month)}`}
      className="featured-period-step calendar-month-step"
      to={pathFor(month)}
    >
      <ChevronIcon direction={direction} />
    </Link>
  );
}

/**
 * The calendar's months: steps to the adjacent months around up to four month chips. The API alone
 * states the bounds, from the current month to the last one inside the forward acquisition bound,
 * so a past month is never offered. Phones keep the steps around the month's own name.
 */
export function ReleaseCalendarMonths({ model, pathFor }: ReleaseCalendarMonthsProps) {
  if (model === null) {
    return <span aria-hidden="true" className="calendar-months calendar-months-pending" />;
  }
  return (
    <nav aria-label="Mes del calendario" className="calendar-months">
      <Step direction="previous" month={model.previous} pathFor={pathFor} />
      <ul className="calendar-month-chips">
        {monthWindow(model.months, model.month).map((month) => (
          <li key={month}>
            <Link
              preventScrollReset
              aria-current={month === model.month ? "true" : undefined}
              aria-label={formatMonth(month)}
              className="calendar-month-chip"
              to={pathFor(month)}
            >
              {formatShortMonth(month)}
            </Link>
          </li>
        ))}
      </ul>
      <p aria-hidden="true" className="calendar-month-name">
        {model.monthLabel}
      </p>
      <Step direction="next" month={model.next} pathFor={pathFor} />
    </nav>
  );
}
