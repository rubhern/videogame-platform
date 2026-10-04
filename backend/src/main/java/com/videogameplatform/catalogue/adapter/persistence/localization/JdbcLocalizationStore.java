package com.videogameplatform.catalogue.adapter.persistence.localization;

import com.videogameplatform.catalogue.application.localization.port.CatalogueTranslationPort.Translation;
import com.videogameplatform.catalogue.application.localization.port.LocalizationStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/** Short PostgreSQL transactions; inference never holds a transaction or revision lock. */
public final class JdbcLocalizationStore implements LocalizationStore {
    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations transaction;

    public JdbcLocalizationStore(
            NamedParameterJdbcOperations jdbc, TransactionOperations transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    private static final String SUMMARY_FILTER =
            "summary_kind='sourced' AND summary_language='en'"
                    + " AND summary_source_kind='external_provider' AND summary_source_name='IGDB'";

    private static final String SUMMARY =
            """
        SELECT 'SUMMARY' AS kind, g.game_id AS id, g.summary_text AS source,
               catalogue.spanish_source_fingerprint(g.summary_text) AS fingerprint,
               COALESCE(l.fingerprint=catalogue.spanish_source_fingerprint(g.summary_text),false) AS current
        FROM catalogue.game_snapshot g
        LEFT JOIN catalogue.game_summary_translation l ON l.game_id=g.game_id
        WHERE g.summary_kind='sourced' AND g.summary_language='en'
          AND g.summary_source_kind='external_provider' AND g.summary_source_name='IGDB'
        """;

    private static String taxonomy(Kind kind) {
        String table = kind == Kind.GENRE ? "genre" : "game_mode";
        return "SELECT '"
                + kind.name()
                + "' AS kind, t."
                + table
                + "_id AS id, t.source_label AS source,"
                + " catalogue.spanish_source_fingerprint(t.source_label) AS fingerprint,"
                + " (t.label_origin='curated' OR COALESCE(t.translation_fingerprint="
                + "catalogue.spanish_source_fingerprint(t.source_label),false)) AS current"
                + " FROM catalogue."
                + table
                + " t WHERE t.source_label IS NOT NULL";
    }

    @Override
    public List<Target> gameTargets(UUID gameId) {
        var targets = new ArrayList<Target>();
        var params = Map.of("game", gameId);
        targets.addAll(query(SUMMARY + " AND g.game_id=:game", params));
        for (Kind kind : List.of(Kind.GENRE, Kind.GAME_MODE)) {
            String table = kind == Kind.GENRE ? "genre" : "game_mode";
            // Acquisition already bounds each Game's metadata; the sentinel rejects corrupt links.
            var terms =
                    query(
                            taxonomy(kind)
                                    + " AND EXISTS (SELECT 1 FROM catalogue.game_"
                                    + table
                                    + " l WHERE l."
                                    + table
                                    + "_id=t."
                                    + table
                                    + "_id AND l.game_id=:game) ORDER BY id LIMIT 51",
                            params);
            if (terms.size() > 50)
                throw new IllegalStateException("Localization metadata bound exceeded");
            targets.addAll(terms);
        }
        return List.copyOf(targets);
    }

    @Override
    public List<Target> targetsAfter(String kind, UUID id, int limit) {
        // Each branch keyset-pages its indexed UUID identity; at most 3*(limit) rows merge.
        var branches = new ArrayList<String>();
        for (Kind candidate : List.of(Kind.GAME_MODE, Kind.GENRE, Kind.SUMMARY)) {
            int order = candidate.name().compareTo(kind);
            if (order < 0) continue;
            String table =
                    candidate == Kind.SUMMARY
                            ? "game_snapshot"
                            : candidate == Kind.GENRE ? "genre" : "game_mode";
            String identity = candidate == Kind.SUMMARY ? "game_id" : table + "_id";
            String filter = candidate == Kind.SUMMARY ? SUMMARY_FILTER : "source_label IS NOT NULL";
            // LIMIT is inside the source relation: hashing and translation joins see only this
            // page.
            String page =
                    "(SELECT * FROM catalogue."
                            + table
                            + " WHERE "
                            + filter
                            + (order == 0 ? " AND " + identity + ">:id" : "")
                            + " ORDER BY "
                            + identity
                            + " LIMIT :limit)";
            String source =
                    candidate == Kind.SUMMARY
                            ? SUMMARY.replace("catalogue.game_snapshot g", page + " g")
                            : taxonomy(candidate).replace("catalogue." + table + " t", page + " t");
            branches.add("(" + source + ")");
        }
        if (branches.isEmpty()) return List.of();
        return query(
                "SELECT * FROM ("
                        + String.join(" UNION ALL ", branches)
                        + ") all_targets ORDER BY kind,id LIMIT :limit",
                Map.of("id", id, "limit", limit));
    }

    private List<Target> query(String sql, Map<String, ?> params) {
        return jdbc.query(
                sql,
                params,
                (rs, row) ->
                        new Target(
                                Kind.valueOf(rs.getString("kind")),
                                rs.getObject("id", UUID.class),
                                rs.getString("source"),
                                rs.getString("fingerprint"),
                                rs.getBoolean("current")));
    }

    @Override
    public Optional<Translation> translated(String fingerprint) {
        return jdbc
                .query(
                        "SELECT translated_text,runtime_revision FROM catalogue.content_translation WHERE fingerprint=:hash AND translated_text IS NOT NULL",
                        Map.of("hash", fingerprint),
                        (rs, row) -> new Translation(rs.getString(1), rs.getString(2)))
                .stream()
                .findFirst();
    }

    @Override
    public Optional<UUID> claim(Target target) {
        UUID claim = UUID.randomUUID();
        int changed =
                jdbc.update(
                        """
            INSERT INTO catalogue.content_translation(fingerprint,source_text,claim_id,claimed_until)
            VALUES (:hash,:source,:claim,clock_timestamp()+interval '5 minutes')
            ON CONFLICT (fingerprint) DO UPDATE SET claim_id=EXCLUDED.claim_id,claimed_until=EXCLUDED.claimed_until
            WHERE content_translation.translated_text IS NULL
              AND (content_translation.claimed_until IS NULL OR content_translation.claimed_until < clock_timestamp())
            """,
                        Map.of(
                                "hash",
                                target.fingerprint(),
                                "source",
                                target.source(),
                                "claim",
                                claim));
        return changed == 1 ? Optional.of(claim) : Optional.empty();
    }

    @Override
    public boolean complete(Target target, UUID claim, Translation translation) {
        return jdbc.update(
                        """
            UPDATE catalogue.content_translation SET translated_text=:text,runtime_revision=:revision,
                translated_at=clock_timestamp(),claim_id=NULL,claimed_until=NULL
            WHERE fingerprint=:hash AND claim_id=:claim AND claimed_until > clock_timestamp()
                AND translated_text IS NULL
            """,
                        Map.of(
                                "hash",
                                target.fingerprint(),
                                "claim",
                                claim,
                                "text",
                                translation.text(),
                                "revision",
                                translation.revision()))
                == 1;
    }

    @Override
    public void release(String fingerprint, UUID claim) {
        jdbc.update(
                "UPDATE catalogue.content_translation SET claim_id=NULL,claimed_until=NULL WHERE fingerprint=:hash AND claim_id=:claim",
                Map.of("hash", fingerprint, "claim", claim));
    }

    @Override
    public boolean publish(Target target) {
        return Boolean.TRUE.equals(
                transaction.execute(
                        status -> {
                            // Same lock ordering as source synchronization; then verify source
                            // under its row lock.
                            jdbc.queryForList(
                                    "SELECT publication_id FROM catalogue.catalogue_publication FOR UPDATE",
                                    Map.of());
                            var parameters =
                                    new MapSqlParameterSource()
                                            .addValue("id", target.id())
                                            .addValue("hash", target.fingerprint());
                            if (target.kind() == Kind.SUMMARY) {
                                var current =
                                        jdbc.query(
                                                """
                    SELECT game_id FROM catalogue.game_snapshot WHERE game_id=:id
                      AND summary_kind='sourced' AND summary_language='en'
                      AND summary_source_kind='external_provider' AND summary_source_name='IGDB'
                      AND catalogue.spanish_source_fingerprint(summary_text)=:hash FOR UPDATE
                    """,
                                                parameters,
                                                (rs, row) -> rs.getObject(1, UUID.class));
                                if (current.isEmpty() || translated(target.fingerprint()).isEmpty())
                                    return false;
                                int changed =
                                        jdbc.update(
                                                """
                    INSERT INTO catalogue.game_summary_translation(game_id,fingerprint) VALUES(:id,:hash)
                    ON CONFLICT (game_id) DO UPDATE SET fingerprint=EXCLUDED.fingerprint
                    WHERE game_summary_translation.fingerprint IS DISTINCT FROM EXCLUDED.fingerprint
                    """,
                                                parameters);
                                if (changed > 0) revision();
                                return true;
                            }
                            String table = target.kind() == Kind.GENRE ? "genre" : "game_mode";
                            int changed =
                                    jdbc.update(
                                            "UPDATE catalogue."
                                                    + table
                                                    + " t SET display_name=c.translated_text,"
                                                    + " label_origin='machine',translation_fingerprint=c.fingerprint"
                                                    + " FROM catalogue.content_translation c WHERE t."
                                                    + table
                                                    + "_id=:id"
                                                    + " AND c.fingerprint=:hash AND c.translated_text IS NOT NULL AND length(c.translated_text)<=200"
                                                    + " AND t.label_origin<>'curated' AND catalogue.spanish_source_fingerprint(t.source_label)=:hash"
                                                    + " AND t.translation_fingerprint IS DISTINCT FROM :hash",
                                            parameters);
                            if (changed > 0) revision();
                            return changed > 0;
                        }));
    }

    private void revision() {
        jdbc.update(
                "UPDATE catalogue.catalogue_publication SET catalogue_version=gen_random_uuid()::text,published_at=clock_timestamp()",
                Map.of());
    }
}
