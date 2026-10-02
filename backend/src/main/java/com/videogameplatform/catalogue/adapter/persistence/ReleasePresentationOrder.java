package com.videogameplatform.catalogue.adapter.persistence;

/**
 * PostgreSQL ordering of the presented-release precedence shared by release discovery and game
 * details (#212).
 *
 * <p>Several stored releases of one game can share a platform: regional releases, early or advance
 * access beside a later date, or an older approximate estimate beside an exact day. Every one of
 * them stays stored and is never merged (REL-005). Ordered by this precedence, the first release of
 * a game and platform, or of a game, platform and region, is the one presented in that context:
 * evidence that is neither cancelled nor delayed, then Full Release, then not pending review, then verified, then the
 * most precise date, then Worldwide before a specific region before the unconfirmed-region
 * sentinel, then the context's own date order. The region code and the unique release id close the
 * order, so the choice never depends on row order.
 */
public final class ReleasePresentationOrder {

    private ReleasePresentationOrder() {}

    /**
     * @param release alias of a {@code catalogue.release_snapshot} row
     * @param region alias of the {@code catalogue.region} row of that release
     * @param dateOrder the context's own date order of {@code release}, without a tie-breaker
     */
    public static String of(String release, String region, String dateOrder) {
        return "CASE "
                + release
                + ".release_status WHEN 'cancelled' THEN 2 WHEN 'delayed' THEN 1 ELSE 0 END, CASE "
                + release
                + ".release_stage WHEN 'full_release' THEN 0 ELSE 1 END, CASE "
                + release
                + ".review_status WHEN 'required' THEN 1 ELSE 0 END, CASE "
                + release
                + ".verification_level WHEN 'verified' THEN 0 ELSE 1 END, "
                + precisionRank(release)
                + ", CASE "
                + region
                + ".code WHEN 'worldwide' THEN 0 WHEN 'unknown' THEN 2 ELSE 1 END, "
                + dateOrder
                + ", "
                + region
                + ".code, "
                + release
                + ".release_id";
    }

    /** Exact day first, then month, quarter and year, and the unknown (TBA) precision last. */
    public static String precisionRank(String release) {
        return "CASE "
                + release
                + ".date_precision WHEN 'day' THEN 1 WHEN 'month' THEN 2"
                + " WHEN 'quarter' THEN 3 WHEN 'year' THEN 4 ELSE 5 END";
    }
}
