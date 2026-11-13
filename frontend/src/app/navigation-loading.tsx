import { useId, useLayoutEffect, useRef } from "react";

import { BrandMark } from "../shared/brand/brand-logo";

const REVEAL_DELAY_MS = 1_000;

export function NavigationLoading({ pending }: { pending: boolean }) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const labelId = useId();

  useLayoutEffect(() => {
    const dialog = dialogRef.current;
    if (!pending || !dialog) return;

    const reveal = window.setTimeout(() => dialog.showModal(), REVEAL_DELAY_MS);
    return () => {
      window.clearTimeout(reveal);
      // Release native focus and interaction before the destination's focus effect.
      if (dialog.open) dialog.close();
    };
  }, [pending]);

  return (
    <dialog
      aria-labelledby={labelId}
      className="navigation-loading"
      onCancel={event => event.preventDefault()}
      onKeyDown={event => event.stopPropagation()}
      ref={dialogRef}
    >
      <div className="navigation-loading-content">
        <div aria-hidden="true" className="navigation-loading-dial">
          <BrandMark className="navigation-loading-mark" />
        </div>
        <p id={labelId}>Cargando página…</p>
      </div>
    </dialog>
  );
}
