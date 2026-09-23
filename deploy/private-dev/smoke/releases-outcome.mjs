export function classifyReleasesOutcome(status, body, check = "releases API") {
  if (status === 200) {
    if (!Array.isArray(body?.items)) {
      throw new Error(`${check} omitted the release items array`);
    }
    if (typeof body.page?.totalItems !== "number") {
      throw new Error(`${check} omitted page metadata`);
    }
    return { empty: body.items.length === 0, state: "published" };
  }
  if (status === 503 && body?.code === "CATALOGUE_NOT_READY") {
    return { empty: true, state: "not-ready" };
  }
  throw new Error(
    `${check} returned unexpected HTTP ${status}${body?.code ? ` (${body.code})` : ""}`,
  );
}
