import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import { Link } from "react-router-dom";

import { SelectIcon } from "../../shared/ui/select-icon";
import { featuredPlatforms, type PlatformContext } from "../platforms/platform-context";

type PlatformSwitcherProps = {
  current: PlatformContext;
  /** The other platforms the directory holds games for; null while it is read or unavailable. */
  others: readonly PlatformContext[] | null;
  pathFor: (context: PlatformContext) => string;
};

/**
 * **Cambiar plataforma**: a disclosure listing the featured platforms, then the other platforms
 * on offer, as native links to the same catalogue that keep the search, state, order and filters.
 * Escape and a press outside close it; the current platform is announced as current.
 */
export function PlatformSwitcher({ current, others, pathFor }: PlatformSwitcherProps) {
  const id = useId();
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!open) return;
    function closeOutside(event: Event) {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    }
    document.addEventListener("pointerdown", closeOutside);
    document.addEventListener("focusin", closeOutside);
    return () => {
      document.removeEventListener("pointerdown", closeOutside);
      document.removeEventListener("focusin", closeOutside);
    };
  }, [open]);

  function closeOnEscape(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape" && open) {
      event.stopPropagation();
      setOpen(false);
      trigger.current?.focus();
    }
  }

  function group(key: string, title: string, platforms: readonly PlatformContext[]) {
    return (
      <div className="platform-switcher-group">
        <p className="platform-switcher-title" id={`${id}-${key}`}>{title}</p>
        <ul aria-labelledby={`${id}-${key}`} className="platform-switcher-list">
          {platforms.map((platform) => (
            <li key={platform.key}>
              <Link
                aria-current={platform.key === current.key ? "page" : undefined}
                className="platform-switcher-option"
                onClick={() => setOpen(false)}
                to={pathFor(platform)}
              >
                <span aria-hidden="true" className="platform-switcher-mark">
                  <SelectIcon name={platform.logo} />
                </span>
                <span>{platform.name}</span>
              </Link>
            </li>
          ))}
        </ul>
      </div>
    );
  }

  return (
    <div className="platform-switcher" onKeyDown={closeOnEscape} ref={root}>
      <button
        aria-controls={`${id}-panel`}
        aria-expanded={open}
        className="platform-switcher-trigger"
        onClick={() => setOpen((value) => !value)}
        ref={trigger}
        type="button"
      >
        <span aria-hidden="true" className="platform-switcher-icon" />
        <span>
          Cambiar <span className="platform-switcher-label-rest">plataforma</span>
        </span>
      </button>
      <div className="platform-switcher-panel" hidden={!open} id={`${id}-panel`}>
        {group("featured", "Destacadas", featuredPlatforms)}
        {others === null || others.length === 0 ? null : group("others", "Otras plataformas", others)}
      </div>
    </div>
  );
}
