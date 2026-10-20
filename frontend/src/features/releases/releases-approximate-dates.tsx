/**
 * **Incluir fechas aproximadas**, offered only while the explored range reaches beyond today
 * (#285). Unchecked by default, the days still to come list exact days; checked, month, quarter
 * and year periods that overlap them join, each at its own precision on every card. A date that is
 * only to be confirmed never joins a range. The choice lives in the URL and returns to the first
 * page, like the other filters.
 */
export function ReleasesApproximateDates({
  checked,
  onChange,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <label className="release-approximate-option">
      <input
        checked={checked}
        onChange={(event) => onChange(event.currentTarget.checked)}
        type="checkbox"
      />
      <span>Incluir fechas aproximadas</span>
    </label>
  );
}
