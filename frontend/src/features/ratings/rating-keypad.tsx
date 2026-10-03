import { useState, type KeyboardEvent, type Ref } from "react";

import { thermalBand, thermalLabels } from "../../shared/score/thermal-band";

const RATING_VALUES = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10] as const;

const FOCUS_STEPS: Record<string, number> = {
  ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1, Home: -10, End: 10,
};

/**
 * The Gameómetro 1–10 keypad: a labelled group of ten circular buttons, each in its own
 * temperature, with a key naming the scale's ends. The pressed value is `value`.
 *
 * <p>Arrow keys, Home and End only move focus (roving tabindex), so browsing the scale never
 * picks a value by accident; Enter, Space or a press calls `onPick`. What a pick means — saving
 * at once or choosing a pending value — belongs to the caller.
 */
export function RatingKeypad({ value, onPick, disabled = false, describedBy, ref }: {
  value: number | null;
  onPick: (value: number) => void;
  disabled?: boolean;
  describedBy?: string | undefined;
  ref?: Ref<HTMLDivElement>;
}) {
  const [focused, setFocused] = useState<number | null>(null);
  const tabStop = focused ?? value ?? 1;

  function moveFocus(event: KeyboardEvent<HTMLDivElement>) {
    const step = FOCUS_STEPS[event.key];
    if (step === undefined) return;
    event.preventDefault();
    const next = Math.min(10, Math.max(1, (focused ?? value ?? 1) + step));
    setFocused(next);
    event.currentTarget.querySelector<HTMLButtonElement>(`button[value="${next}"]`)?.focus();
  }

  return (
    <>
      <div
        ref={ref}
        className="rating-scale"
        role="group"
        aria-label="Nota del 1 al 10"
        aria-describedby={describedBy}
        onKeyDown={moveFocus}
      >
        {RATING_VALUES.map((option) => (
          <button
            key={option}
            type="button"
            className="rating-option"
            data-thermal={thermalBand(option) ?? undefined}
            value={option}
            aria-pressed={value === option}
            disabled={disabled}
            tabIndex={tabStop === option ? 0 : -1}
            onFocus={() => setFocused(option)}
            onClick={() => onPick(option)}
          >
            {option}
          </button>
        ))}
      </div>
      {/* The scale's key: the keypad runs from ice to fire. */}
      <p className="rating-thermal-scale">
        <span data-thermal="freeze">1 · {thermalLabels.freeze}</span>
        <span data-thermal="burn">10 · {thermalLabels.burn}</span>
      </p>
    </>
  );
}
