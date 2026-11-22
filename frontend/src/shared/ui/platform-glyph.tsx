/**
 * The one generic platform glyph: four tiles, a shelf of systems. It marks Mis plataformas, the
 * Plataforma selectors and any platform without its own logo, and stays distinct from the gamepad
 * of Mis juegos. Drawn on a 20×20 view box; the caller owns the svg element.
 */
export function PlatformGlyphShapes() {
  return (
    <>
      <rect height="6" rx="1.2" stroke="currentColor" strokeWidth="1.5" width="6" x="2.75" y="2.75" />
      <rect height="6" rx="1.2" stroke="currentColor" strokeWidth="1.5" width="6" x="11.25" y="2.75" />
      <rect height="6" rx="1.2" stroke="currentColor" strokeWidth="1.5" width="6" x="2.75" y="11.25" />
      <rect height="6" rx="1.2" stroke="currentColor" strokeWidth="1.5" width="6" x="11.25" y="11.25" />
    </>
  );
}
