/** Decorative line icons of the featured releases; every one is hidden from assistive technology. */

export function CalendarIcon() {
  return (
    <svg aria-hidden="true" className="featured-icon" fill="none" viewBox="0 0 20 20">
      <rect x="2.5" y="4.5" width="15" height="13" rx="2" stroke="currentColor" strokeWidth="1.5" />
      <path d="M6 2.5v4M14 2.5v4M2.5 8.5h15" stroke="currentColor" strokeLinecap="round" strokeWidth="1.5" />
    </svg>
  );
}

/** A four-pointed spark: an automatic selection, never a medal or an award. */
export function SparkIcon() {
  return (
    <svg aria-hidden="true" className="featured-icon" fill="none" viewBox="0 0 20 20">
      <path
        d="M10 1.8c.5 4.3 1.9 5.7 6.2 6.2-4.3.5-5.7 1.9-6.2 6.2-.5-4.3-1.9-5.7-6.2-6.2 4.3-.5 5.7-1.9 6.2-6.2Z"
        fill="currentColor"
      />
      <path
        d="M15.6 13.2c.2 1.7.8 2.3 2.4 2.5-1.6.2-2.2.8-2.4 2.5-.2-1.7-.8-2.3-2.4-2.5 1.6-.2 2.2-.8 2.4-2.5Z"
        fill="currentColor"
        opacity="0.7"
      />
    </svg>
  );
}

export function ChevronIcon({ direction }: { direction: "previous" | "next" }) {
  return (
    <svg aria-hidden="true" className="featured-icon" fill="none" viewBox="0 0 20 20">
      <path
        d={direction === "previous" ? "m12 5-5 5 5 5" : "m8 5 5 5-5 5"}
        stroke="currentColor"
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth="1.7"
      />
    </svg>
  );
}
