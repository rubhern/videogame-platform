import { useId, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";

import {
  MAX_RANGE_DAYS,
  rangeProblem,
  releasesSearchPath,
  type RangeProblem,
  type ReleasesSearch,
} from "./releases-search";

type ReleasesRangeProps = {
  search: ReleasesSearch;
  /** The range the API evaluated, which fills the fields when the URL names none. */
  evaluated: { from: string; to: string } | null;
  onApply: (from: string, to: string) => void;
};

const problems: Record<RangeProblem, string> = {
  incomplete: "Elige el día de inicio y el de fin",
  reversed: "El día de fin no puede ser anterior al de inicio",
  "too-long": `Elige como máximo ${MAX_RANGE_DAYS} días`,
};

/**
 * The explored range as two native, labelled date fields, both inclusive (#285). A range applies
 * on **Aplicar** or Enter, never while a date is half typed, and a range the API would reject is
 * explained in place instead of being sent. **Esta semana** returns to the current week, which
 * only the API's trusted date knows. Mount it with a key per evaluated range so the fields follow
 * the URL.
 */
export function ReleasesRange({ search, evaluated, onApply }: ReleasesRangeProps) {
  const id = useId();
  const [from, setFrom] = useState(search.from ?? evaluated?.from ?? "");
  const [to, setTo] = useState(search.to ?? evaluated?.to ?? "");
  const [problem, setProblem] = useState<RangeProblem | null>(null);
  const errorId = `${id}-error`;

  function apply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const found = rangeProblem(from, to);
    setProblem(found);
    if (found === null) onApply(from, to);
  }

  return (
    <form aria-label="Periodo de lanzamientos" className="release-range" noValidate onSubmit={apply}>
      <label className="release-range-field">
        <span>Desde</span>
        <input
          aria-describedby={problem === null ? undefined : errorId}
          aria-invalid={problem === null ? undefined : true}
          max={to === "" ? undefined : to}
          onChange={(event) => setFrom(event.currentTarget.value)}
          type="date"
          value={from}
        />
      </label>
      <label className="release-range-field">
        <span>Hasta</span>
        <input
          aria-describedby={problem === null ? undefined : errorId}
          aria-invalid={problem === null ? undefined : true}
          min={from === "" ? undefined : from}
          onChange={(event) => setTo(event.currentTarget.value)}
          type="date"
          value={to}
        />
      </label>
      <button className="button release-range-apply" type="submit">
        Aplicar
      </button>
      {search.from === null ? null : (
        <Link
          className="filter-reset release-range-reset"
          preventScrollReset
          to={releasesSearchPath(search, { from: null, to: null, page: 1 })}
        >
          Esta semana
        </Link>
      )}
      {problem === null ? null : (
        <p className="release-range-error" id={errorId} role="alert">
          {problems[problem]}
        </p>
      )}
    </form>
  );
}
