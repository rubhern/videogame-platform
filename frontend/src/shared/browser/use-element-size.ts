import { useLayoutEffect, useState, type RefObject } from "react";

export type ElementSize = { width: number; height: number };

/**
 * The layout size of an element, kept current as its content or the viewport changes. It is
 * measured before the first paint, so geometry drawn around the element never visibly jumps, and
 * it stays null where layout is unavailable (an element that is not rendered, or a test DOM).
 */
export function useElementSize(ref: RefObject<HTMLElement | null>): ElementSize | null {
  const [size, setSize] = useState<ElementSize | null>(null);

  useLayoutEffect(() => {
    const element = ref.current;
    if (element === null) {
      return;
    }
    const measure = () => {
      const width = element.offsetWidth;
      const height = element.offsetHeight;
      setSize((previous) => {
        if (width === 0 || height === 0) return null;
        return previous?.width === width && previous.height === height
          ? previous
          : { width, height };
      });
    };
    measure();
    if (typeof ResizeObserver === "undefined") {
      return;
    }
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, [ref]);

  return size;
}
