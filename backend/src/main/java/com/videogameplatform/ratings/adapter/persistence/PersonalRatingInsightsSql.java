package com.videogameplatform.ratings.adapter.persistence;

/** Production statements are shared with the opt-in PostgreSQL plan evidence. */
final class PersonalRatingInsightsSql {
    private PersonalRatingInsightsSql() {}

    // Each rating counts once, in its contribution's half-up integer bucket; the mean averages the
    // unrounded contributions themselves.
    static final String DISTRIBUTION =
            """
            WITH scoped AS MATERIALIZED (
                SELECT contribution FROM ratings.rating WHERE user_id=:user
            ), buckets AS (
                SELECT %s AS score, count(*) AS count FROM scoped GROUP BY 1
            ), totals AS (
                SELECT count(*) AS total, %s AS mean FROM scoped
            )
            SELECT series.score, coalesce(b.count,0) AS count, t.total, t.mean
            FROM generate_series(1,10) series(score) CROSS JOIN totals t
            LEFT JOIN buckets b ON b.score=series.score ORDER BY series.score
            """
                    .formatted(
                            ContributionMean.bucket("contribution"),
                            ContributionMean.exposed("contribution"));

    static final String COVERAGE =
            """
            SELECT count(*) FILTER (WHERE NOT EXISTS (
                       SELECT 1 FROM ratings.game_listing_genre g WHERE g.game_id=r.game_id
                   )) AS missing_genres,
                   count(*) FILTER (WHERE r.created_at < :start) AS older
            FROM ratings.rating r WHERE r.user_id=:user
            """;

    static String genres(boolean bestRated) {
        return """
                WITH scoped AS MATERIALIZED (
                    SELECT game_id,contribution FROM ratings.rating WHERE user_id=:user
                ), aggregates AS (
                    SELECT g.genre_code,count(*) AS count,%s AS mean
                    FROM scoped r JOIN ratings.game_listing_genre g ON g.game_id=r.game_id
                    GROUP BY g.genre_code
                ), selected AS (
                    SELECT * FROM aggregates
                """
                        .formatted(ContributionMean.exact("r.contribution"))
                + (bestRated
                        ? " WHERE count>=3 ORDER BY mean DESC,count DESC,genre_code LIMIT 6"
                        : " ORDER BY count DESC,genre_code LIMIT 6")
                + """
                ) SELECT s.genre_code,t.display_name,s.count,round(s.mean,1) AS mean
                  FROM selected s JOIN ratings.genre_label t USING (genre_code)
                  ORDER BY
                """
                + (bestRated
                        ? "s.mean DESC,s.count DESC,s.genre_code"
                        : "s.count DESC,s.genre_code");
    }

    /**
     * The rated game with the highest or lowest unrounded contribution, the smallest game id among
     * equal ones, and how many games share exactly that contribution.
     */
    static String extreme(boolean highest) {
        return """
                WITH scoped AS MATERIALIZED (
                    SELECT game_id,contribution FROM ratings.rating WHERE user_id=:user
                ), chosen AS (
                    SELECT game_id,contribution FROM scoped
                    ORDER BY contribution %s,game_id LIMIT 1
                )
                SELECT c.game_id,round(c.contribution,1) AS value,
                       (SELECT count(*) FROM scoped s WHERE s.contribution=c.contribution) AS tied,
                       g.slug,g.canonical_title,g.cover_kind,g.cover_reference,
                       g.cover_alternative_text,g.cover_attribution,g.cover_source_url
                FROM chosen c JOIN ratings.game_listing g USING (game_id)
                """
                .formatted(highest ? "DESC" : "ASC");
    }

    static final String ACTIVITY =
            """
            WITH months AS (
                SELECT date_trunc('month',created_at AT TIME ZONE 'Europe/Madrid')::date AS month,
                       count(*) AS count
                FROM ratings.rating WHERE user_id=:user AND created_at>=:start AND created_at<:end
                GROUP BY 1
            )
            SELECT to_char(series.month,'YYYY-MM') AS month,coalesce(m.count,0) AS count
            FROM generate_series(CAST(:firstMonth AS date),CAST(:lastMonth AS date),interval '1 month')
                series(month)
            LEFT JOIN months m ON m.month=series.month::date ORDER BY series.month
            """;
}
