import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import type { WishlistMove } from "./wishlist-api";

type Row = { gameId: string; title: string; position: number };
export type MoveCommand = { source: Row; target: Row; move: WishlistMove };
type Gesture = {
  source: Row;
  x: number;
  y: number;
  moved: boolean;
  pointerId: number;
};

export function useWishlistReorder(
  rows: readonly Row[],
  scope: number | undefined,
  busy: boolean,
  onMove: (command: MoveCommand) => void,
) {
  const [selection, setSelection] = useState<{ source: Row; scope: number | undefined } | null>(null);
  const [destination, setDestination] = useState<Row | null>(null);
  const gesture = useRef<Gesture | null>(null);
  const frame = useRef(0);
  const ignoreClick = useRef(false);
  const source = selection?.scope === scope ? selection?.source : undefined;
  const target = source === undefined ? null : destination;

  useEffect(() => () => cancelAnimationFrame(frame.current), []);

  function clear() {
    cancelAnimationFrame(frame.current);
    frame.current = 0;
    gesture.current = null;
    setSelection(null);
    setDestination(null);
  }

  function commit(from: Row, to: Row) {
    clear();
    if (busy || from.gameId === to.gameId) return;
    onMove({
      source: from,
      target: to,
      move: {
        anchorGameId: to.gameId,
        placement: from.position < to.position ? "after" : "before",
      },
    });
  }

  function targetAt(x: number, y: number) {
    const item = document.elementFromPoint(x, y)?.closest<HTMLElement>("[data-wishlist-position]");
    return rows.find((row) => row.position === Number(item?.dataset.wishlistPosition)) ?? null;
  }

  // Reading the target after scrolling keeps the drop line attached to a row as it enters view.
  function previewPointer() {
    frame.current = 0;
    const active = gesture.current;
    if (!active?.moved) return;
    const direction = active.y < 64 ? -1 : active.y > window.innerHeight - 64 ? 1 : 0;
    if (direction) window.scrollBy(0, direction * 14);
    const row = targetAt(active.x, active.y);
    const newTarget = row?.gameId === active.source.gameId ? null : row;
    setDestination((previous) => previous?.gameId === newTarget?.gameId ? previous : newTarget);
    if (direction) frame.current = requestAnimationFrame(previewPointer);
  }

  function handle(row: Row) {
    return {
      "aria-pressed": source?.gameId === row.gameId,
      onClick: (event: React.MouseEvent<HTMLButtonElement>) => {
        if (ignoreClick.current && event.detail !== 0) {
          ignoreClick.current = false;
          return;
        }
        ignoreClick.current = false;
        if (busy) return;
        if (source === undefined) {
          setSelection({ source: row, scope });
        } else if (source.gameId === row.gameId) {
          if (target) commit(source, target);
          else clear();
        } else {
          commit(source, row);
        }
      },
      onKeyDown: (event: KeyboardEvent<HTMLButtonElement>) => {
        if (!source || busy) return;
        if (event.key === "Escape") {
          event.preventDefault();
          clear();
        } else if (event.key === "ArrowUp" || event.key === "ArrowDown") {
          event.preventDefault();
          const step = event.key === "ArrowUp" ? -1 : 1;
          const next = rows.find((item) => item.position === (target ?? source).position + step);
          if (next) setDestination(next.gameId === source.gameId ? null : next);
        }
      },
      onPointerDown: (event: PointerEvent<HTMLButtonElement>) => {
        if (!event.isPrimary || event.button !== 0 || busy) return;
        ignoreClick.current = false;
        event.currentTarget.setPointerCapture(event.pointerId);
        gesture.current = { source: row, x: event.clientX, y: event.clientY, moved: false, pointerId: event.pointerId };
      },
      onPointerMove: (event: PointerEvent<HTMLButtonElement>) => {
        const active = gesture.current;
        if (!active || active.pointerId !== event.pointerId) return;
        if (!active.moved) {
          if (Math.hypot(event.clientX - active.x, event.clientY - active.y) < 5) return;
          active.moved = true;
          setSelection({ source: row, scope });
        }
        active.x = event.clientX;
        active.y = event.clientY;
        if (!frame.current) frame.current = requestAnimationFrame(previewPointer);
      },
      onPointerUp: (event: PointerEvent<HTMLButtonElement>) => {
        const active = gesture.current;
        if (!active || active.pointerId !== event.pointerId) return;
        if (!active.moved) {
          gesture.current = null;
          return;
        }
        ignoreClick.current = true;
        const rowAtRelease = targetAt(event.clientX, event.clientY);
        if (rowAtRelease) commit(active.source, rowAtRelease);
        else clear();
      },
      onPointerCancel: clear,
      onLostPointerCapture: () => { if (gesture.current) clear(); },
    };
  }

  return {
    handle,
    source,
    target,
    announcement: target
      ? `Puesto ${target.position}`
      : source ? `${source.title}, puesto ${source.position}. Elige el destino.` : "",
  };
}
