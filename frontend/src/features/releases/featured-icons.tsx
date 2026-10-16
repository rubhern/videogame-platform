/** Decorative line icons of the featured releases; every one is hidden from assistive technology. */

export function CalendarIcon() {
  return (
    <svg aria-hidden="true" className="featured-icon" fill="none" viewBox="0 0 20 20">
      <rect x="2.5" y="4.5" width="15" height="13" rx="2" stroke="currentColor" strokeWidth="1.5" />
      <path d="M6 2.5v4M14 2.5v4M2.5 8.5h15" stroke="currentColor" strokeLinecap="round" strokeWidth="1.5" />
    </svg>
  );
}

/**
 * The brand mark's fire needle, the sign of the month's featured release: a reading taken by the
 * catalogue, never a medal or an award.
 */
export function NeedleIcon() {
  return (
    <svg aria-hidden="true" className="featured-icon featured-needle-icon" viewBox="26 4 26 34">
      <path d="M34.32 33.57 49.61 5.89 29.68 30.43Z" fill="currentColor" />
      <circle cx="32" cy="32" fill="currentColor" r="4.6" />
      <circle className="featured-needle-core" cx="32" cy="32" r="1.56" />
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
