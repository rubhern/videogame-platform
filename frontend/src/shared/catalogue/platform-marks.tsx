import { SelectIcon, type SelectIconName } from "../ui/select-icon";

export type PlatformMark = { key: string; name: string; icon: SelectIconName };

type PlatformMarksProps = {
  /** Accessible name of the list, for example `Plataformas de Hades II`. */
  label: string;
  marks: readonly PlatformMark[];
  /** Marks shown on screen; the rest stay named for assistive technology. */
  visible?: number;
  /** Further platforms the response only counts, announced with their own sentence. */
  more?: { count: number; label: string } | undefined;
};

/**
 * A listing's platforms as their marks: every name stays announced and is the mark's tooltip, an
 * unrecognized platform keeps its name beside the generic mark, and the exact number of platforms
 * not drawn follows as `+N`. Marks are decorative; their names carry the list.
 */
export function PlatformMarks({ label, marks, visible = marks.length, more }: PlatformMarksProps) {
  const unseen = Math.max(0, marks.length - visible) + (more?.count ?? 0);
  return (
    <ul aria-label={label} className="platform-marks">
      {marks.map((mark, index) => (
        <li
          className={index < visible ? "platform-mark" : "sr-only"}
          key={mark.key}
          title={mark.name}
        >
          {index < visible ? <SelectIcon name={mark.icon} /> : null}
          <span className={mark.icon === "platform" && index < visible ? "platform-mark-name" : "sr-only"}>
            {mark.name}
          </span>
        </li>
      ))}
      {unseen === 0 ? null : more !== undefined && more.count > 0 ? (
        <li className="platform-mark-more" title={more.label}>
          <span aria-hidden="true">+{unseen}</span>
          <span className="sr-only">{more.label}</span>
        </li>
      ) : (
        // Every platform beyond the marks is already named above; the count is for sight only.
        <li aria-hidden="true" className="platform-mark-more">
          +{unseen}
        </li>
      )}
    </ul>
  );
}
