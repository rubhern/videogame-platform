import { afterEach, describe, expect, it } from "vitest";

import { forgetMyRatingsQueries, recallMyRatingsQuery, rememberMyRatingsQuery } from "./my-ratings-memory";

afterEach(() => forgetMyRatingsQueries());

describe("Mis puntuaciones query memory", () => {
  it("recalls a history entry's personal query from document memory only", () => {
    rememberMyRatingsQuery("entry-1", { q: "élite", sort: "ratingValue", direction: "asc", page: 2, pageSize: 20 });

    expect(recallMyRatingsQuery("entry-1")).toEqual({
      q: "élite",
      sort: "ratingValue",
      direction: "asc",
      page: 2,
      pageSize: 20,
    });
    expect(recallMyRatingsQuery("entry-2")).toBeUndefined();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it("keeps a bounded number of entries, forgetting the oldest first", () => {
    for (let index = 0; index < 21; index += 1) {
      rememberMyRatingsQuery(`entry-${index}`, { page: index + 1, pageSize: 10 });
    }

    expect(recallMyRatingsQuery("entry-0")).toBeUndefined();
    expect(recallMyRatingsQuery("entry-20")).toEqual({ page: 21, pageSize: 10 });
  });
});
