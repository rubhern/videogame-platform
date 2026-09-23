import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";

import { SelectIcon, type SelectIconName } from "./select-icon";

export type MultiSelectOption = {
  value: string;
  label: string;
  icon?: SelectIconName;
};

type MultiSelectProps = {
  /** Visible dimension label rendered above the control and part of the trigger's accessible name. */
  label: string;
  /** Dimension icon shown on the trigger and used as the per-option fallback icon. */
  icon: SelectIconName;
  options: readonly MultiSelectOption[];
  selected: readonly string[];
  onToggle: (value: string) => void;
  /** Closed-state text when nothing is selected (e.g. "Todas"); never itself a selectable value. */
  allLabel: string;
  className?: string;
  /** Optional closed-state summary override; defaults to name, name +N, or "N seleccionadas". */
  summarize?: (selectedLabels: readonly string[], allLabel: string) => string;
};

function defaultSummary(selectedLabels: readonly string[], allLabel: string): string {
  if (selectedLabels.length === 0) {
    return allLabel;
  }
  if (selectedLabels.length === 1) {
    return selectedLabels[0] ?? allLabel;
  }
  return `${selectedLabels.length} seleccionadas`;
}

/**
 * A compact multi-select dropdown: a combobox trigger that summarises the current selection and a
 * multi-selectable listbox popover of checkbox options. Toggling an option keeps the popover open;
 * clicking outside, Escape, or the trigger closes it. Reused for every filter dimension so Platform
 * and Region share one accessible implementation. Selection semantics (OR within, AND across
 * dimensions) live in the caller; this control only reports which values were toggled.
 */
export function MultiSelect({
  label,
  icon,
  options,
  selected,
  onToggle,
  allLabel,
  className = "",
  summarize = defaultSummary,
}: MultiSelectProps) {
  const id = useId();
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const [expanded, setExpanded] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const active = options.length === 0 ? -1 : Math.min(activeIndex, options.length - 1);
  const selectedSet = new Set(selected);
  const selectedLabels = options
    .filter((option) => selectedSet.has(option.value))
    .map((option) => option.label);
  const summary = summarize(selectedLabels, allLabel);
  const labelId = `${id}-label`;
  const valueId = `${id}-value`;
  const optionId = (index: number) => `${id}-option-${index}`;

  useEffect(() => {
    if (!expanded) {
      return;
    }
    function closeOutside(event: Event) {
      if (!root.current?.contains(event.target as Node)) {
        setExpanded(false);
      }
    }
    document.addEventListener("pointerdown", closeOutside);
    document.addEventListener("focusin", closeOutside);
    return () => {
      document.removeEventListener("pointerdown", closeOutside);
      document.removeEventListener("focusin", closeOutside);
    };
  }, [expanded]);

  useEffect(() => {
    if (expanded && active >= 0) {
      document.getElementById(`${id}-option-${active}`)?.scrollIntoView?.({ block: "nearest" });
    }
  }, [active, expanded, id]);

  function open() {
    if (options.length === 0) {
      return;
    }
    const firstSelected = options.findIndex((option) => selectedSet.has(option.value));
    setActiveIndex(firstSelected >= 0 ? firstSelected : 0);
    setExpanded(true);
  }

  function toggle(index: number) {
    const option = options[index];
    if (option) {
      onToggle(option.value);
    }
  }

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    if (event.key === "Escape" && expanded) {
      event.preventDefault();
      setExpanded(false);
      return;
    }
    if (event.key === "Tab") {
      setExpanded(false);
      return;
    }
    if (options.length === 0) {
      return;
    }
    if (
      event.key === "ArrowDown" ||
      event.key === "ArrowUp" ||
      event.key === "Home" ||
      event.key === "End"
    ) {
      event.preventDefault();
      if (!expanded) {
        open();
        return;
      }
      if (event.key === "Home") {
        setActiveIndex(0);
      } else if (event.key === "End") {
        setActiveIndex(options.length - 1);
      } else {
        setActiveIndex((active + (event.key === "ArrowDown" ? 1 : options.length - 1)) % options.length);
      }
      return;
    }
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      if (!expanded) {
        open();
      } else if (active >= 0) {
        toggle(active);
      }
    }
  }

  return (
    <div className={`app-select app-select-inline app-multiselect ${className}`} ref={root}>
      <button
        aria-activedescendant={expanded && active >= 0 ? optionId(active) : undefined}
        aria-controls={`${id}-options`}
        aria-expanded={expanded}
        aria-haspopup="listbox"
        aria-labelledby={`${labelId} ${valueId}`}
        className="app-select-trigger"
        disabled={options.length === 0}
        onClick={() => (expanded ? setExpanded(false) : open())}
        onKeyDown={onKeyDown}
        ref={trigger}
        role="combobox"
        type="button"
      >
        <SelectIcon name={icon} />
        <span className="app-select-inline-label" id={labelId}>
          {label}:
        </span>
        <strong className="app-select-value" id={valueId}>
          {summary}
        </strong>
        <span aria-hidden="true" className="app-select-chevron" />
      </button>
      <ul
        aria-label={label}
        aria-multiselectable="true"
        className="app-select-options app-multiselect-options"
        hidden={!expanded}
        id={`${id}-options`}
        role="listbox"
      >
        {options.map((option, index) => {
          const checked = selectedSet.has(option.value);
          return (
            <li
              aria-selected={checked}
              className="app-multiselect-option"
              data-active={active === index ? "true" : undefined}
              id={optionId(index)}
              key={option.value}
              onClick={() => toggle(index)}
              onMouseDown={(event) => event.preventDefault()}
              onPointerMove={() => setActiveIndex(index)}
              role="option"
            >
              <span
                aria-hidden="true"
                className="app-multiselect-check"
                data-checked={checked ? "true" : undefined}
              />
              <SelectIcon name={option.icon ?? icon} />
              <span className="app-multiselect-text">{option.label}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
