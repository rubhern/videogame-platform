package com.videogameplatform.catalogue.adapter.persistence.releases;

import com.videogameplatform.catalogue.domain.AttentionWeights;
import com.videogameplatform.catalogue.domain.ReleaseAttentionPolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@link ReleaseAttentionPolicy} as SQL, shared by every attention-ordered read so the release
 * explorer, Destacados, the calendar's chosen day and Próximos destacados rank one candidate set
 * identically (ATT-001 to ATT-006).
 *
 * <p>Each candidate Game reads its Hypes, its Want to Play and Visits shares and its editorial
 * score through primary-key or indexed probes, so the work follows the range's candidates, never
 * the catalogue or the evidence tables. The scale is relative to the strongest candidate of the
 * same set, which the window maximum reads once over the candidates the order already sorts. The
 * operator's weights ({@link AttentionWeights}) are bound as parameters, so one statement text
 * serves every setting and a change applies from the next read on.
 */
final class ReleaseAttentionSql {

    /** The binding names of the range the attention is computed for. */
    static final String FROM_PARAMETER = "attentionFrom";

    static final String TO_PARAMETER = "attentionTo";

    /** The launch stages as an SQL list of trusted literals (ATT-007). */
    static final String LAUNCH_STAGES =
            ReleaseAttentionPolicy.LAUNCH_STAGES.stream()
                    .map(stage -> "'" + stage.value() + "'")
                    .collect(java.util.stream.Collectors.joining(", ", "(", ")"));

    private ReleaseAttentionSql() {}

    /**
     * Two common table expressions, {@code attention_raw} and {@code game_signal}, without a leading
     * or trailing comma. {@code game_signal} holds every row of {@code source} with its {@code
     * attention} (NULL without any signal) and, as {@code signal_observed_at}, the oldest
     * acquisition or loading time among the evidence it read.
     *
     * <p>Only a launch draws attention (ATT-007): a row whose {@code full_launch} is false has no
     * level, so it never sets the scale and its attention is NULL. A Game is a re-release when one
     * of its launch releases in the current publication, neither cancelled nor delayed, ended
     * before the range; the probe walks that Game's releases by the publication, game and period
     * index.
     *
     * @param source a common table expression with one row per candidate Game, its {@code game_id}
     *     and whether a launch release of it is in the range ({@code full_launch})
     */
    static String signals(String source) {
        String rerelease = "earlier.launched";
        return """
                attention_raw AS MATERIALIZED (
                    SELECT src.*,
                           least(hype.observed_at, share.observed_at, editorial.loaded_at)
                               AS signal_observed_at,
                           editorial.score AS editorial_score,
                           CASE WHEN src.full_launch THEN
                           ln(1 + coalesce(CASE WHEN %1$s THEN hype.hypes * %2$s
                                   ELSE greatest(hype.hypes, share.parent_hypes * %3$s) END, 0))
                           END AS hype_level,
                           CASE WHEN src.full_launch THEN
                           ln(1 + %4$s * coalesce(CASE WHEN %1$s THEN share.want_to_play * %2$s
                                   ELSE greatest(share.want_to_play, share.parent_want_to_play * %3$s)
                                   END, 0))
                           END AS want_level,
                           CASE WHEN src.full_launch THEN
                           ln(1 + %4$s * coalesce(CASE WHEN %1$s THEN share.visits * %2$s
                                   ELSE greatest(share.visits, share.parent_visits * %3$s) END, 0))
                           END AS visit_level
                    FROM %5$s src
                    LEFT JOIN LATERAL (
                        SELECT evidence.hypes, evidence.observed_at
                        FROM catalogue.game_featured_evidence evidence
                        WHERE evidence.game_id = src.game_id
                        LIMIT 1
                    ) hype ON true
                    CROSS JOIN LATERAL (
                        SELECT EXISTS (
                            SELECT 1
                            FROM catalogue.release_snapshot launch
                            WHERE launch.publication_id = CAST(:publicationId AS uuid)
                              AND launch.game_id = src.game_id
                              AND launch.period_start < CAST(:attentionFrom AS date)
                              AND launch.period_end < CAST(:attentionFrom AS date)
                              AND launch.release_stage IN %9$s
                              AND launch.release_status NOT IN ('cancelled', 'delayed')
                        ) AS launched
                        -- Evaluated once per candidate, not once per signal that reads it.
                        OFFSET 0
                    ) earlier
                    LEFT JOIN LATERAL (
                        SELECT attention.want_to_play, attention.visits, attention.parent_hypes,
                               attention.parent_want_to_play, attention.parent_visits,
                               attention.observed_at
                        FROM catalogue.game_attention_evidence attention
                        WHERE attention.game_id = src.game_id
                        LIMIT 1
                    ) share ON true
                    LEFT JOIN LATERAL (
                        SELECT max(entry.score) AS score, min(list.loaded_at) AS loaded_at
                        FROM catalogue.editorial_attention_entry entry
                        JOIN catalogue.editorial_attention_list list ON list.list_id = entry.list_id
                        WHERE entry.game_id = src.game_id
                          AND daterange(list.range_from, list.range_to, '[]')
                              && daterange(CAST(:attentionFrom AS date), CAST(:attentionTo AS date), '[]')
                    ) editorial ON true
                ),
                game_signal AS (
                    SELECT ar.*,
                           CASE WHEN ar.full_launch
                                     AND (ar.hype_level > 0 OR ar.want_level > 0
                                          OR ar.visit_level > 0 OR ar.editorial_score IS NOT NULL)
                           THEN round(CAST(
                               (1 - %7$s) * 100 * (
                                   :attentionHypeWeight
                                     * coalesce(ar.hype_level / nullif(max(ar.hype_level) OVER (), 0), 0)
                                   + :attentionWantToPlayWeight
                                     * coalesce(ar.want_level / nullif(max(ar.want_level) OVER (), 0), 0)
                                   + :attentionVisitsWeight
                                     * coalesce(ar.visit_level / nullif(max(ar.visit_level) OVER (), 0), 0)
                               ) / %6$s
                               + %7$s * coalesce(ar.editorial_score, 0) AS numeric), %8$d)
                           END AS attention
                    FROM attention_raw ar
                )"""
                .formatted(
                        rerelease,
                        "CAST(:attentionReReleaseShare AS numeric)",
                        "CAST(:attentionInheritedShare AS numeric)",
                        decimal(ReleaseAttentionPolicy.SHARE_RESOLUTION),
                        source,
                        "(CAST(:attentionHypeWeight AS numeric)"
                                + " + CAST(:attentionWantToPlayWeight AS numeric)"
                                + " + CAST(:attentionVisitsWeight AS numeric))",
                        "CAST(:attentionEditorialWeight AS numeric)",
                        ReleaseAttentionPolicy.SCALE,
                        LAUNCH_STAGES);
    }

    /** The bindings of the range the attention is computed for, with the approved weights. */
    static Map<String, Object> parameters(LocalDate from, LocalDate to) {
        return parameters(from, to, AttentionWeights.DEFAULT);
    }

    /** The bindings of the range the attention is computed for and of the weights it applies. */
    static Map<String, Object> parameters(LocalDate from, LocalDate to, AttentionWeights weights) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(FROM_PARAMETER, from);
        parameters.put(TO_PARAMETER, to);
        parameters.put("attentionHypeWeight", numeric(weights.hypes()));
        parameters.put("attentionWantToPlayWeight", numeric(weights.wantToPlay()));
        parameters.put("attentionVisitsWeight", numeric(weights.visits()));
        parameters.put("attentionEditorialWeight", numeric(weights.editorial()));
        parameters.put("attentionInheritedShare", numeric(weights.inheritedShare()));
        parameters.put("attentionReReleaseShare", numeric(weights.reReleaseShare()));
        return parameters;
    }

    private static BigDecimal numeric(double value) {
        return new BigDecimal(Double.toString(value));
    }

    /** A trusted constant as an SQL numeric literal, never in exponent notation. */
    private static String decimal(double value) {
        return new java.math.BigDecimal(Double.toString(value))
                .toPlainString()
                .toLowerCase(Locale.ROOT);
    }
}
