import { describe, expect, expectTypeOf, it } from "vitest";

import type { components } from "../api/generated/schema";
import {
  formatCompactCalendarDay,
  formatReleaseDate,
  formatReleaseDateShort,
} from "./release-date";

type GeneratedReleaseDate = components["schemas"]["ReleaseDate"];
type ExpectedReleaseDateUnion =
  | components["schemas"]["DayReleaseDate"]
  | components["schemas"]["MonthReleaseDate"]
  | components["schemas"]["QuarterReleaseDate"]
  | components["schemas"]["YearReleaseDate"]
  | components["schemas"]["UnknownReleaseDate"];

describe("generated release date oneOf", () => {
  it("formats API window boundaries compactly for phones", () => {
    expect(formatCompactCalendarDay("2026-08-26")).toBe("26/08/2026");
    expect(formatCompactCalendarDay("2026-09-20")).toBe("20/09/2026");
  });
  it("retains every reviewed generated variant and its nullable unknown value", () => {
    expectTypeOf<GeneratedReleaseDate>().toEqualTypeOf<ExpectedReleaseDateUnion>();
    expectTypeOf<components["schemas"]["DayReleaseDate"]["value"]>().toEqualTypeOf<string>();
    expectTypeOf<components["schemas"]["UnknownReleaseDate"]["value"]>().toEqualTypeOf<null>();
  });

  it.each([
    [{ precision: "day", value: "2026-02-27" }, "27 de febrero de 2026"],
    [{ precision: "month", value: "2026-09" }, "septiembre de 2026"],
    [{ precision: "quarter", value: "2026-Q1" }, "1.er trimestre de 2026"],
    [{ precision: "quarter", value: "2026-Q2" }, "2.º trimestre de 2026"],
    [{ precision: "quarter", value: "2026-Q3" }, "3.er trimestre de 2026"],
    [{ precision: "quarter", value: "2026-Q4" }, "4.º trimestre de 2026"],
    [{ precision: "year", value: "2027" }, "2027"],
    [{ precision: "unknown", value: null }, "Fecha por confirmar"],
  ] as const)("formats %o without inventing precision", (releaseDate, expected) => {
    expect(formatReleaseDate(releaseDate)).toBe(expected);
  });
});

describe("compact release date chip", () => {
  it.each([
    [{ precision: "day", value: "2026-02-07" }, "7 feb 2026"],
    [{ precision: "day", value: "2026-09-25" }, "25 sep 2026"],
    [{ precision: "month", value: "2026-09" }, "sep 2026"],
    [{ precision: "quarter", value: "2026-Q3" }, "T3 2026"],
    [{ precision: "year", value: "2027" }, "2027"],
    [{ precision: "unknown", value: null }, "Por confirmar"],
  ] as const)("keeps the precision of %o", (releaseDate, expected) => {
    expect(formatReleaseDateShort(releaseDate)).toBe(expected);
  });

  it("never turns a malformed value into a date", () => {
    expect(formatReleaseDateShort({ precision: "day", value: "2026-13-01" })).toBe(
      "Fecha no disponible",
    );
    expect(formatReleaseDateShort({ precision: "quarter", value: "2026-Q5" })).toBe(
      "Fecha no disponible",
    );
  });
});
