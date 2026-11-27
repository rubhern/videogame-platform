import type { ReactNode } from "react";

/**
 * A zone's explanation behind a quiet native disclosure, closed by default. Every one reads
 * **Más info**; its accessible name adds the subject (`sobre el borrado`) so several stay distinct.
 * Pass `open` and `onToggle` to open it from elsewhere, as the deletion confirmation does.
 */
export function MoreInfo({ about, children, open, onToggle }: {
  about: string;
  children: ReactNode;
  open?: boolean;
  onToggle?: (open: boolean) => void;
}) {
  return (
    <details
      className="profile-more"
      onToggle={onToggle ? (event) => onToggle(event.currentTarget.open) : undefined}
      open={open}
    >
      <summary>
        Más info<span className="sr-only"> {about}</span>
      </summary>
      <div className="profile-more-body">{children}</div>
    </details>
  );
}
