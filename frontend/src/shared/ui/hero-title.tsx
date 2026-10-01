type HeroTitleProps = {
  id: string;
  /** Set in the wide display cut. */
  lead: string;
  /** Set in the lit italic serif; the heading reads `lead accent` to assistive technology. */
  accent: string;
};

/** Beyond this length a title (usually a visitor's query) steps down a size to stay a title. */
const LONG_TITLE = 26;

/** The page `h1` over a cinematic stage: a display lead and one editorial accent. */
export function HeroTitle({ id, lead, accent }: HeroTitleProps) {
  const long = lead.length + accent.length > LONG_TITLE;
  return (
    <h1 className={`page-title hero-title${long ? " hero-title-long" : ""}`} id={id}>
      {lead} <span className="title-accent">{accent}</span>
    </h1>
  );
}
