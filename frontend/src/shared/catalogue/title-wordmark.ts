/**
 * A canonical title set as a product-owned wordmark: the name, then the subtitle an explicit
 * delimiter introduces. The lockup drops the delimiter visually; assistive technology and the
 * heading's text still read the canonical title unchanged.
 */
export type TitleWordmark = {
  main: string;
  /** `:` or a spaced dash, as the canonical title writes it; empty without a subtitle. */
  delimiter: string;
  sub: string | null;
};

/** A subtitle follows the first colon or spaced dash, as in `Onimusha: Way of the Sword`. */
const SUBTITLE = /^(.+?)(:| [-–—]) (.+)$/u;

export function toWordmark(title: string): TitleWordmark {
  const match = SUBTITLE.exec(title);
  const [, main = "", delimiter = "", sub = ""] = match ?? [];
  return main.trim() === "" || sub.trim() === ""
    ? { main: title, delimiter: "", sub: null }
    : { main, delimiter, sub };
}
