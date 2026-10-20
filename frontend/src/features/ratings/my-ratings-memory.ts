import type { MyRatingsQuery } from "./my-ratings-api";

/**
 * The personal collection's search, order and page per history entry, so a return from a game
 * page or the browser's Back restores them (#285). It lives only in this document's memory, like
 * saved reading positions: the personal query never enters the URL, history state or browser
 * storage, and a reload starts from the default collection again.
 */
const remembered = new Map<string, MyRatingsQuery>();

/** A bound on entries kept, oldest first out; one visit adds at most one. */
const MAX_ENTRIES = 20;

export function rememberMyRatingsQuery(entry: string, query: MyRatingsQuery): void {
  remembered.delete(entry);
  remembered.set(entry, query);
  if (remembered.size > MAX_ENTRIES) {
    const oldest = remembered.keys().next().value;
    if (oldest !== undefined) remembered.delete(oldest);
  }
}

export function recallMyRatingsQuery(entry: string): MyRatingsQuery | undefined {
  return remembered.get(entry);
}

/** Test seam: forgets every remembered query. */
export function forgetMyRatingsQueries(): void {
  remembered.clear();
}
