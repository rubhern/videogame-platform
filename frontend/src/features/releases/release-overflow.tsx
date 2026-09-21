import { useEffect, useId, useRef, useState } from "react";

import type { ReleaseContextGroup } from "./releases-view-model";

type ReleaseOverflowProps = {
  title: string;
  hiddenReleaseCount: number;
  groups: ReleaseContextGroup[];
};

function overflowLabel(hiddenReleaseCount: number): string {
  return hiddenReleaseCount === 1
    ? "+ 1 lanzamiento más"
    : `+ ${hiddenReleaseCount} lanzamientos más`;
}

/**
 * Keeps the card compact: the hidden release groups live in a lightweight popover instead of
 * adding rows. The trigger reports how many releases are hidden; opening moves focus into the
 * dialog, Escape or an outside click closes it and returns focus to the trigger.
 */
export function ReleaseOverflow({ title, hiddenReleaseCount, groups }: ReleaseOverflowProps) {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const dialogId = useId();

  useEffect(() => {
    if (!open) {
      return;
    }
    dialogRef.current?.focus();

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setOpen(false);
        triggerRef.current?.focus();
      }
    }
    function onPointerDown(event: PointerEvent) {
      const target = event.target as Node;
      if (!dialogRef.current?.contains(target) && !triggerRef.current?.contains(target)) {
        setOpen(false);
      }
    }
    document.addEventListener("keydown", onKeyDown);
    document.addEventListener("pointerdown", onPointerDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("pointerdown", onPointerDown);
    };
  }, [open]);

  return (
    <div className="release-overflow">
      <button
        ref={triggerRef}
        type="button"
        className="release-overflow-toggle"
        aria-expanded={open}
        aria-haspopup="dialog"
        aria-controls={open ? dialogId : undefined}
        onClick={() => setOpen((previous) => !previous)}
      >
        {overflowLabel(hiddenReleaseCount)}
      </button>
      {open ? (
        <div
          ref={dialogRef}
          id={dialogId}
          role="dialog"
          aria-label={`Otros lanzamientos de ${title}`}
          className="release-overflow-popover"
          tabIndex={-1}
        >
          <ul className="release-overflow-list">
            {groups.map((group) => (
              <li key={group.key}>
                <span className="search-release-date">{group.date}</span>
                <span className="card-platform">
                  {group.platforms.join(" · ")} · {group.region}
                </span>
                {group.review ? (
                  <span className="badge badge-warning">Información pendiente de revisión</span>
                ) : null}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}
