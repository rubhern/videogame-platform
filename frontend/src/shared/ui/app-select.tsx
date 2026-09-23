import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";

import { SelectIcon, type SelectIconName } from "./select-icon";

export type AppSelectOption = {
  value: string;
  label: string;
  icon?: SelectIconName;
};

type AppSelectProps = {
  label: string;
  value: string;
  options: readonly AppSelectOption[];
  onChange: (value: string) => void;
  icon: SelectIconName;
  className?: string;
  inlineLabel?: boolean;
  autoFocus?: boolean;
  disabled?: boolean;
};

/** A select-only combobox: one full-size trigger, with a styled and keyboard-reachable list. */
export function AppSelect({
  label, value, options, onChange, icon, className = "", inlineLabel = false,
  autoFocus = false, disabled = false,
}: AppSelectProps) {
  const id = useId();
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const search = useRef({ text: "", at: 0 });
  const [expanded, setExpanded] = useState(false);
  const selectedIndex = Math.max(options.findIndex((option) => option.value === value), 0);
  const [activeIndex, setActiveIndex] = useState(selectedIndex);
  const active = Math.min(activeIndex, Math.max(options.length - 1, 0));
  const selected = options[selectedIndex];
  const optionId = (index: number) => `${id}-option-${index}`;

  useEffect(() => {
    if (!expanded) return;
    function closeOutside(event: Event) {
      if (!root.current?.contains(event.target as Node)) setExpanded(false);
    }
    document.addEventListener("pointerdown", closeOutside);
    document.addEventListener("focusin", closeOutside);
    return () => {
      document.removeEventListener("pointerdown", closeOutside);
      document.removeEventListener("focusin", closeOutside);
    };
  }, [expanded]);

  useEffect(() => {
    if (expanded) document.getElementById(`${id}-option-${active}`)?.scrollIntoView?.({ block: "nearest" });
  }, [active, expanded, id]);

  function open() {
    if (disabled || options.length === 0) return;
    setActiveIndex(selectedIndex);
    setExpanded(true);
  }

  function choose(index: number) {
    const option = options[index];
    if (!option) return;
    setExpanded(false);
    trigger.current?.focus();
    if (option.value !== value) onChange(option.value);
  }

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    if (options.length === 0) return;
    if (event.key === "Escape" && expanded) {
      event.preventDefault();
      setExpanded(false);
      return;
    }
    if (event.key === "Tab") {
      setExpanded(false);
      return;
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp" || event.key === "Home" || event.key === "End") {
      event.preventDefault();
      if (!expanded) {
        open();
      } else if (event.key === "Home") {
        setActiveIndex(0);
      } else if (event.key === "End") {
        setActiveIndex(options.length - 1);
      } else {
        setActiveIndex((active + (event.key === "ArrowDown" ? 1 : options.length - 1)) % options.length);
      }
      return;
    }
    if (expanded && (event.key === "Enter" || event.key === " ")) {
      event.preventDefault();
      choose(active);
      return;
    }
    if (event.key.length === 1 && !event.altKey && !event.ctrlKey && !event.metaKey && event.key !== " ") {
      const now = Date.now();
      search.current.text = now - search.current.at < 700
        ? search.current.text + event.key.toLocaleLowerCase("es")
        : event.key.toLocaleLowerCase("es");
      search.current.at = now;
      const match = options.findIndex((option) => option.label.toLocaleLowerCase("es").startsWith(search.current.text));
      if (match >= 0) {
        event.preventDefault();
        if (!expanded) open();
        setActiveIndex(match);
      }
    }
  }

  return (
    <div className={`app-select ${inlineLabel ? "app-select-inline" : ""} ${className}`} ref={root}>
      {inlineLabel ? null : <span className="app-select-label" id={`${id}-label`}>{label}</span>}
      <button
        aria-activedescendant={expanded && options.length > 0 ? optionId(active) : undefined}
        aria-controls={`${id}-options`}
        aria-expanded={expanded}
        aria-haspopup="listbox"
        aria-labelledby={`${id}-label ${id}-value`}
        autoFocus={autoFocus}
        className="app-select-trigger"
        disabled={disabled}
        onClick={() => expanded ? setExpanded(false) : open()}
        onKeyDown={onKeyDown}
        ref={trigger}
        role="combobox"
        type="button"
      >
        <SelectIcon name={selected?.icon ?? icon} />
        {inlineLabel ? <span className="app-select-inline-label" id={`${id}-label`}>{label}</span> : null}
        <strong className="app-select-value" id={`${id}-value`}>{selected?.label ?? value}</strong>
        <span aria-hidden="true" className="app-select-chevron" />
      </button>
      <div aria-label={label} className="app-select-options" hidden={!expanded} id={`${id}-options`} role="listbox">
        {options.map((option, index) => (
          <button
            aria-selected={option.value === value}
            className="app-select-option"
            data-active={active === index ? "true" : undefined}
            id={optionId(index)}
            key={option.value}
            onClick={() => choose(index)}
            onPointerMove={() => setActiveIndex(index)}
            role="option"
            tabIndex={-1}
            type="button"
          >
            <SelectIcon name={option.icon ?? icon} />
            <span>{option.label}</span>
            {option.value === value ? <span aria-hidden="true" className="app-select-check">✓</span> : null}
          </button>
        ))}
      </div>
    </div>
  );
}
