import type { CSSProperties } from "react";

type CinematicStageProps =
  | { variant: "releases" | "search" | "collection" }
  /** A game's page is lit by the cover it already displays. */
  | { variant: "cover"; coverUrl: string };

// Fixed positions keep every render identical: the motes rise from the valley lights on the
// night art and drift through the light on a cover.
const MOTES = [
  { x: "52%", y: "24%", s: "4px", t: "11s", d: "0s", drift: "22px" },
  { x: "58%", y: "30%", s: "3px", t: "9s", d: "2.4s", drift: "-14px" },
  { x: "63%", y: "18%", s: "5px", t: "13s", d: "5.1s", drift: "30px" },
  { x: "68%", y: "34%", s: "3px", t: "8s", d: "1.2s", drift: "12px" },
  { x: "72%", y: "22%", s: "4px", t: "12s", d: "6.6s", drift: "-26px" },
  { x: "77%", y: "28%", s: "3px", t: "10s", d: "3.8s", drift: "18px" },
  { x: "81%", y: "16%", s: "3px", t: "14s", d: "8.2s", drift: "-10px" },
  { x: "86%", y: "32%", s: "3px", t: "9s", d: "4.4s", drift: "24px" },
  { x: "44%", y: "36%", s: "3px", t: "12s", d: "7.3s", drift: "16px" },
  { x: "91%", y: "24%", s: "4px", t: "11s", d: "9.5s", drift: "-20px" },
  { x: "35%", y: "20%", s: "3px", t: "15s", d: "10.8s", drift: "20px" },
  { x: "56%", y: "12%", s: "3px", t: "10s", d: "11.9s", drift: "-16px" },
] as const;

/**
 * The decorative stage behind a page's opening: art, grade, mist and rising motes. It is hidden
 * from assistive technology, takes no pointer input and never carries readable content.
 */
export function CinematicStage(props: CinematicStageProps) {
  const style =
    props.variant === "cover"
      ? ({ "--hero-art": `url(${JSON.stringify(props.coverUrl)})` } as CSSProperties)
      : undefined;

  return (
    <div aria-hidden="true" className={`cinema cinema-${props.variant}`} style={style}>
      <div className="cinema-art" />
      <div className="cinema-fog" />
      <div className="cinema-fog" />
      <div className="cinema-motes">
        {MOTES.map((mote) => (
          <span
            key={`${mote.x}-${mote.y}`}
            style={
              {
                "--x": mote.x,
                "--y": mote.y,
                "--s": mote.s,
                "--t": mote.t,
                "--d": mote.d,
                "--drift": mote.drift,
              } as CSSProperties
            }
          />
        ))}
      </div>
    </div>
  );
}
