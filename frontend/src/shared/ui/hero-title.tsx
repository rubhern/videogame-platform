type HeroTitleProps = {
  id: string;
  /** The page's own title, read as one phrase. */
  title: string;
};

/** Beyond this length a title (usually a visitor's query) steps down a size to stay a title. */
const LONG_TITLE = 26;

/** The page `h1` in the wide display cut, as TOPómetro sets it. */
export function HeroTitle({ id, title }: HeroTitleProps) {
  return (
    <h1 className={`page-title hero-title${title.length > LONG_TITLE ? " hero-title-long" : ""}`} id={id}>
      {title}
    </h1>
  );
}
