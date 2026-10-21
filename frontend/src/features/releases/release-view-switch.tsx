import { Link } from "react-router-dom";

type ReleaseViewSwitchProps = {
  current: "list" | "calendar";
  /** The explorer and the calendar, each carrying what the other view can keep. */
  listPath: string;
  calendarPath: string;
};

/**
 * **Lista | Calendario** (#250): the two views of **Lanzamientos**, as TOPómetro's
 * **Semana | Mes** switch reads. Both are native links, so the view lives in the URL and the main
 * navigation keeps its two entries.
 */
export function ReleaseViewSwitch({ current, listPath, calendarPath }: ReleaseViewSwitchProps) {
  const options = [
    { value: "list", label: "Lista", to: listPath },
    { value: "calendar", label: "Calendario", to: calendarPath },
  ] as const;
  return (
    <nav aria-label="Vista de lanzamientos" className="release-view-switch featured-granularity">
      <ul>
        {options.map((option) => (
          <li key={option.value}>
            <Link
              aria-current={option.value === current ? "page" : undefined}
              className="featured-granularity-option"
              to={option.to}
            >
              {option.label}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  );
}
