import { useEffect, useId, useRef, useState } from "react";

import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import { SelectIcon } from "../../shared/ui/select-icon";
import type { GameDetails } from "./game-details-api";

type AvailableStatistics = Extract<GameDetails["ratingStatistics"], { distribution: unknown }>;
type PlatformReading = AvailableStatistics["platforms"][number];

const decimal = (value: number) =>
  value.toLocaleString("es-ES", { minimumFractionDigits: 1, maximumFractionDigits: 1 });

/**
 * The community's readings per platform, behind one quiet control beside the count: each
 * platform's own mean and how many players scored it there, in plain numbers without meters or
 * temperatures, so the community score keeps its authority. A player who scores several
 * platforms counts once in that score, which the panel states, so platform counts never read as
 * voters. Without any platform score the control is absent rather than empty.
 */
export function CommunityPlatformScores({
  platforms,
  title,
}: {
  platforms: readonly PlatformReading[];
  title: string;
}) {
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const panelId = useId();

  useEffect(() => {
    if (!open) return;
    panel.current?.focus();
    function onKeyDown(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      setOpen(false);
      trigger.current?.focus();
    }
    function onPointerDown(event: PointerEvent) {
      const target = event.target;
      if (!(target instanceof Node)) return;
      if (!panel.current?.contains(target) && !trigger.current?.contains(target)) setOpen(false);
    }
    document.addEventListener("keydown", onKeyDown);
    document.addEventListener("pointerdown", onPointerDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("pointerdown", onPointerDown);
    };
  }, [open]);

  if (platforms.length === 0) return null;

  return (
    <div
      className="community-platforms"
      onBlur={(event) => {
        const next = event.relatedTarget;
        if (open && next instanceof Node && !event.currentTarget.contains(next)) setOpen(false);
      }}
    >
      <button
        aria-controls={open ? panelId : undefined}
        aria-expanded={open}
        aria-haspopup="dialog"
        className="community-platforms-toggle"
        onClick={() => setOpen((previous) => !previous)}
        ref={trigger}
        type="button"
      >
        Por plataforma
        <span aria-hidden="true" className="game-rating-caret" />
      </button>
      {open ? (
        <div
          aria-label={`Puntuación de la comunidad por plataforma en ${title}`}
          className="community-platforms-panel"
          id={panelId}
          ref={panel}
          role="dialog"
          tabIndex={-1}
        >
          <ul className="community-platforms-list">
            {platforms.map((reading) => (
              <li key={reading.platform.platformId}>
                <SelectIcon
                  name={platformIcon(reading.platform.platformId, reading.platform.name)}
                />
                <span className="community-platforms-name">{reading.platform.name}</span>
                <strong aria-label={`Media: ${decimal(reading.mean)} de 10`}>
                  {decimal(reading.mean)}
                </strong>
                <span className="community-platforms-count">
                  {reading.count.toLocaleString("es-ES")}{" "}
                  {reading.count === 1 ? "puntuación" : "puntuaciones"}
                </span>
              </li>
            ))}
          </ul>
          <p className="community-platforms-note">
            Quien puntúa varias plataformas cuenta una sola vez en la puntuación de la comunidad.
          </p>
        </div>
      ) : null}
    </div>
  );
}
