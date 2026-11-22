import { PlatformGlyphShapes } from "../../shared/ui/platform-glyph";

/** The player's own destinations, drawn once for the account menu, the home and Mi perfil. */

export function ProfileIcon() {
  return (
    <svg aria-hidden="true" fill="none" viewBox="0 0 20 20">
      <circle cx="10" cy="6" r="3" stroke="currentColor" strokeWidth="1.5" />
      <path d="M4 17v-2a6 6 0 0 1 12 0v2H4Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.5" />
    </svg>
  );
}

export function WishlistIcon() {
  return (
    <svg aria-hidden="true" fill="none" viewBox="0 0 20 20">
      <path d="M10 16.5s-6.5-3.9-6.5-8.4A3.6 3.6 0 0 1 10 6a3.6 3.6 0 0 1 6.5 2.1c0 4.5-6.5 8.4-6.5 8.4Z"
        stroke="currentColor" strokeLinejoin="round" strokeWidth="1.5" />
    </svg>
  );
}

export function LibraryIcon() {
  return (
    <svg aria-hidden="true" fill="none" viewBox="0 0 20 20">
      <rect height="10" rx="3" stroke="currentColor" strokeWidth="1.5" width="16" x="2" y="5" />
      <path d="M6 8.5v3M4.5 10h3" stroke="currentColor" strokeLinecap="round" strokeWidth="1.5" />
      <circle cx="13" cy="9" fill="currentColor" r="1" />
      <circle cx="15" cy="11" fill="currentColor" r="1" />
    </svg>
  );
}

export function RatingsIcon() {
  return (
    <svg aria-hidden="true" fill="none" viewBox="0 0 20 20">
      <path d="M10 2.5 12.3 7l5 .7-3.6 3.5.8 5-4.5-2.3-4.5 2.3.8-5L2.7 7l5-.7L10 2.5Z" stroke="currentColor" strokeLinejoin="round" strokeWidth="1.5" />
    </svg>
  );
}

export function StatisticsIcon() {
  return (
    <svg aria-hidden="true" fill="none" viewBox="0 0 20 20">
      <path d="M3 16V10m7 6V4m7 12V7" stroke="currentColor" strokeWidth="2" />
    </svg>
  );
}

export function PlatformsIcon() {
  return (
    <svg aria-hidden="true" fill="none" viewBox="0 0 20 20">
      <PlatformGlyphShapes />
    </svg>
  );
}
