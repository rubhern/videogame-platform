import { useEffect } from "react";

/**
 * Names the browser tab while a screen is shown, and restores the title it found when the screen
 * goes. The server writes the same title into the landing page's head (ADR-0049, #537), so a
 * crawler that runs JavaScript keeps reading it. `null` leaves the current title alone.
 */
export function useDocumentTitle(title: string | null) {
  useEffect(() => {
    if (title === null) return;
    const previousTitle = document.title;
    document.title = title;
    return () => {
      document.title = previousTitle;
    };
  }, [title]);
}
