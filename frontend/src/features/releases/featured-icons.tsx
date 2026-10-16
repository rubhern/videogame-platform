/** Decorative line icons of the featured releases; every one is hidden from assistive technology. */

export function CalendarIcon() {
  return (
    <svg aria-hidden="true" className="featured-icon" fill="none" viewBox="0 0 20 20">
      <rect x="2.5" y="4.5" width="15" height="13" rx="2" stroke="currentColor" strokeWidth="1.5" />
      <path d="M6 2.5v4M14 2.5v4M2.5 8.5h15" stroke="currentColor" strokeLinecap="round" strokeWidth="1.5" />
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
