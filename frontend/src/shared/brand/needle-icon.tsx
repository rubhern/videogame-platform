/**
 * The brand mark's fire needle with its pivot and warm core: the sign of a reading or a release's
 * date, never a medal or an award. Decorative; the text beside it carries the meaning.
 */
export function NeedleIcon({ className }: { className: string }) {
  return (
    <svg aria-hidden="true" className={className} focusable="false" viewBox="26 4 26 34">
      <path d="M34.32 33.57 49.61 5.89 29.68 30.43Z" fill="currentColor" />
      <circle cx="32" cy="32" fill="currentColor" r="4.6" />
      <circle className="needle-core" cx="32" cy="32" r="1.56" />
    </svg>
  );
}
