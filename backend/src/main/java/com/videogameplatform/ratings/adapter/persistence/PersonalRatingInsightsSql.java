package com.videogameplatform.ratings.adapter.persistence;

/** Production statements are shared with the opt-in PostgreSQL plan evidence. */
final class PersonalRatingInsightsSql {
    private PersonalRatingInsightsSql() {}

    static final String DISTRIBUTION =
            """
            WITH buckets AS (
                SELECT value, count(*) AS count FROM ratings.rating
                WHERE user_id=:user GROUP BY value
            )
            SELECT score, coalesce(count,0) AS count,
                sum(coalesce(count,0)) OVER () AS total,
                round(sum(score * coalesce(count,0)) OVER ()::numeric /
                    nullif(sum(coalesce(count,0)) OVER (),0),1) AS mean
            FROM generate_series(1,10) score LEFT JOIN buckets ON value=score ORDER BY score
            """;

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
                    SELECT game_id,value FROM ratings.rating WHERE user_id=:user
                ), aggregates AS (
                    SELECT g.genre_code,count(*) AS count,avg(r.value) AS mean
                    FROM scoped r JOIN ratings.game_listing_genre g ON g.game_id=r.game_id
                    GROUP BY g.genre_code
                ), selected AS (
                    SELECT * FROM aggregates
                """
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

    static final String EXTREME =
            """
            SELECT r.game_id,r.value,g.slug,g.canonical_title,g.cover_kind,g.cover_reference,
                   g.cover_alternative_text,g.cover_attribution,g.cover_source_url
            FROM (
                SELECT game_id,value FROM ratings.rating
                WHERE user_id=:user AND value=:value ORDER BY game_id LIMIT 1
            ) r JOIN ratings.game_listing g USING (game_id)
            """;

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
