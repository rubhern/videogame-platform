package com.videogameplatform.catalogue.adapter.persistence.attention;

import com.videogameplatform.catalogue.application.administration.EditorialAttentionLists.ListDetail;
import com.videogameplatform.catalogue.application.administration.EditorialAttentionLists.ListEntry;
import com.videogameplatform.catalogue.application.administration.EditorialAttentionLists.ListSummary;
import com.videogameplatform.catalogue.application.attention.port.EditorialAttentionStore;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/**
 * PostgreSQL store of editorial anticipation lists. Catalogue lookups read the current publication
 * by indexed keys only: the IGDB page address the snapshot keeps for attribution, the normalized
 * title, or the Game identity. The exclusion constraint keeps list ranges disjoint even under
 * concurrent loads.
 */
public final class JdbcEditorialAttentionStore implements EditorialAttentionStore {

    /** The provider page address a Game's IGDB slug completes, as acquisition stores it. */
    private static final String PROVIDER_PAGE = "https://www.igdb.com/games/";

    private static final String CURRENT_SNAPSHOT =
            """
            catalogue.game_snapshot gs
            JOIN catalogue.catalogue_publication publication
              ON publication.publication_id = gs.publication_id AND publication.is_current
            """;

    private static final String SUMMARY_COLUMNS =
            """
            l.list_id, l.range_from, l.range_to, l.prompt_version, l.generator_model,
            l.generated_at, l.loaded_at,
            (SELECT count(*) FROM catalogue.editorial_attention_entry e WHERE e.list_id = l.list_id)
                AS entry_count
            """;

    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations transaction;

    public JdbcEditorialAttentionStore(
            NamedParameterJdbcOperations jdbc, TransactionOperations transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public Map<String, GameCandidate> findByProviderSlug(Set<String> slugs) {
        Map<String, String> pages = new HashMap<>();
        slugs.forEach(slug -> pages.put(PROVIDER_PAGE + slug, slug));
        Map<String, GameCandidate> found = new HashMap<>();
        jdbc.query(
                "SELECT gs.cover_source_url, gs.game_id::text, gs.canonical_title, gs.slug FROM "
                        + CURRENT_SNAPSHOT
                        + " WHERE gs.cover_source_url IN (:pages) ORDER BY gs.game_id",
                Map.of("pages", List.copyOf(pages.keySet())),
                (ResultSet rs) -> {
                    found.putIfAbsent(
                            pages.get(rs.getString(1)),
                            new GameCandidate(rs.getString(2), rs.getString(3), rs.getString(4)));
                });
        return found;
    }

    @Override
    public Map<String, List<GameCandidate>> findByTitle(Set<String> titles, int perTitle) {
        Map<String, List<GameCandidate>> found = new HashMap<>();
        List<String> ordered = List.copyOf(titles);
        jdbc.query(
                """
                SELECT wanted.title, candidate.game_id, candidate.canonical_title, candidate.slug
                FROM unnest(CAST(:titles AS text[])) AS wanted(title)
                JOIN LATERAL (
                    SELECT gs.game_id::text AS game_id, gs.canonical_title, gs.slug
                    FROM %s
                    WHERE gs.normalized_title = catalogue.normalize_search_text(wanted.title)
                    ORDER BY gs.game_id
                    LIMIT :perTitle
                ) candidate ON true
                ORDER BY wanted.title, candidate.game_id
                """
                        .formatted(CURRENT_SNAPSHOT),
                new MapSqlParameterSource()
                        .addValue("titles", ordered.toArray(String[]::new))
                        .addValue("perTitle", perTitle),
                (ResultSet rs) -> {
                    found.computeIfAbsent(rs.getString(1), title -> new ArrayList<>())
                            .add(
                                    new GameCandidate(
                                            rs.getString(2), rs.getString(3), rs.getString(4)));
                });
        return found;
    }

    @Override
    public Map<String, GameCandidate> findByIds(Set<String> gameIds) {
        Map<String, GameCandidate> found = new HashMap<>();
        jdbc.query(
                "SELECT gs.game_id::text, gs.canonical_title, gs.slug FROM "
                        + CURRENT_SNAPSHOT
                        + " WHERE gs.game_id = ANY(CAST(:ids AS uuid[]))",
                Map.of("ids", uuids(gameIds)),
                (ResultSet rs) -> {
                    found.put(
                            rs.getString(1),
                            new GameCandidate(rs.getString(1), rs.getString(2), rs.getString(3)));
                });
        return found;
    }

    @Override
    public Map<String, RangeReleases> releasesInRange(
            Set<String> gameIds, LocalDate from, LocalDate to) {
        Map<String, Set<LocalDate>> days = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT rs.game_id::text, rs.exact_date
                FROM catalogue.release_snapshot rs
                JOIN catalogue.catalogue_publication publication
                  ON publication.publication_id = rs.publication_id AND publication.is_current
                WHERE rs.game_id = ANY(CAST(:ids AS uuid[]))
                  AND rs.release_status <> 'cancelled'
                  AND rs.period_start IS NOT NULL AND rs.period_end IS NOT NULL
                  AND daterange(rs.period_start, rs.period_end, '[]')
                      && daterange(CAST(:from AS date), CAST(:to AS date), '[]')
                """,
                new MapSqlParameterSource()
                        .addValue("ids", uuids(gameIds))
                        .addValue("from", from)
                        .addValue("to", to),
                (ResultSet rs) -> {
                    Set<LocalDate> gameDays =
                            days.computeIfAbsent(rs.getString(1), game -> new HashSet<>());
                    LocalDate exact = rs.getObject(2, LocalDate.class);
                    if (exact != null && !exact.isBefore(from) && !exact.isAfter(to)) {
                        gameDays.add(exact);
                    }
                });
        Map<String, RangeReleases> releases = new LinkedHashMap<>();
        days.forEach((game, exact) -> releases.put(game, new RangeReleases(exact)));
        return releases;
    }

    @Override
    public List<ListSummary> overlapping(LocalDate from, LocalDate to) {
        return jdbc.query(
                "SELECT "
                        + SUMMARY_COLUMNS
                        + " FROM catalogue.editorial_attention_list l"
                        + " WHERE daterange(l.range_from, l.range_to, '[]')"
                        + " && daterange(CAST(:from AS date), CAST(:to AS date), '[]')"
                        + " ORDER BY l.range_from, l.list_id",
                new MapSqlParameterSource().addValue("from", from).addValue("to", to),
                (rs, row) -> summary(rs));
    }

    @Override
    public List<ListSummary> lists(int limit) {
        return jdbc.query(
                "SELECT "
                        + SUMMARY_COLUMNS
                        + " FROM catalogue.editorial_attention_list l"
                        + " ORDER BY l.range_from DESC, l.list_id LIMIT :limit",
                Map.of("limit", limit),
                (rs, row) -> summary(rs));
    }

    @Override
    public Optional<ListDetail> find(UUID listId) {
        List<ListSummary> summaries =
                jdbc.query(
                        "SELECT "
                                + SUMMARY_COLUMNS
                                + " FROM catalogue.editorial_attention_list l"
                                + " WHERE l.list_id = :id",
                        Map.of("id", listId),
                        (rs, row) -> summary(rs));
        if (summaries.isEmpty()) {
            return Optional.empty();
        }
        List<ListEntry> entries =
                jdbc.query(
                        """
                        SELECT e.entry_rank, e.game_id::text AS game_id, gs.canonical_title,
                               gs.slug, e.file_title, e.score, e.rationale, e.sources
                        FROM catalogue.editorial_attention_entry e
                        JOIN catalogue.game_snapshot gs ON gs.game_id = e.game_id
                        JOIN catalogue.catalogue_publication publication
                          ON publication.publication_id = gs.publication_id
                         AND publication.is_current
                        WHERE e.list_id = :id
                        ORDER BY e.entry_rank
                        """,
                        Map.of("id", listId),
                        (rs, row) ->
                                new ListEntry(
                                        rs.getInt("entry_rank"),
                                        rs.getString("game_id"),
                                        rs.getString("canonical_title"),
                                        rs.getString("slug"),
                                        rs.getString("file_title"),
                                        rs.getInt("score"),
                                        Optional.ofNullable(rs.getString("rationale")),
                                        sources(rs.getArray("sources"))));
        return Optional.of(new ListDetail(summaries.getFirst(), entries));
    }

    @Override
    public Optional<UUID> save(NewList list, Optional<UUID> replaced) {
        try {
            return transaction.execute(
                    status -> {
                        replaced.ifPresent(
                                id ->
                                        jdbc.update(
                                                "DELETE FROM catalogue.editorial_attention_list"
                                                        + " WHERE list_id = :id",
                                                Map.of("id", id)));
                        jdbc.update(
                                """
                                INSERT INTO catalogue.editorial_attention_list(
                                    list_id, range_from, range_to, prompt_version,
                                    generator_model, generated_at, loaded_at)
                                VALUES (:id, :from, :to, :prompt, :model, :generatedAt, :loadedAt)
                                """,
                                new MapSqlParameterSource()
                                        .addValue("id", list.listId())
                                        .addValue("from", list.from())
                                        .addValue("to", list.to())
                                        .addValue("prompt", list.promptVersion())
                                        .addValue("model", list.model().orElse(null))
                                        .addValue(
                                                "generatedAt",
                                                list.generatedAt()
                                                        .map(this::timestamp)
                                                        .orElse(null))
                                        .addValue("loadedAt", timestamp(list.loadedAt())));
                        jdbc.batchUpdate(
                                """
                                INSERT INTO catalogue.editorial_attention_entry(
                                    list_id, game_id, entry_rank, score, file_title, rationale,
                                    sources)
                                VALUES (:list, CAST(:game AS uuid), :rank, :score, :title,
                                    :rationale, CAST(:sources AS text[]))
                                """,
                                list.entries().stream()
                                        .map(
                                                entry ->
                                                        new MapSqlParameterSource()
                                                                .addValue("list", list.listId())
                                                                .addValue("game", entry.gameId())
                                                                .addValue("rank", entry.rank())
                                                                .addValue("score", entry.score())
                                                                .addValue(
                                                                        "title", entry.fileTitle())
                                                                .addValue(
                                                                        "rationale",
                                                                        entry.rationale()
                                                                                .orElse(null))
                                                                .addValue(
                                                                        "sources",
                                                                        entry.sources()
                                                                                .toArray(
                                                                                        String[]
                                                                                                ::new)))
                                        .toArray(MapSqlParameterSource[]::new));
                        return Optional.of(list.listId());
                    });
        } catch (DataIntegrityViolationException overlap) {
            if (isOverlap(overlap)) {
                return Optional.empty();
            }
            throw overlap;
        }
    }

    @Override
    public boolean delete(UUID listId) {
        return jdbc.update(
                        "DELETE FROM catalogue.editorial_attention_list WHERE list_id = :id",
                        Map.of("id", listId))
                == 1;
    }

    /** Only well-formed identities reach the cast; any other names no Game. */
    private static String[] uuids(Set<String> gameIds) {
        return gameIds.stream()
                .filter(
                        id -> {
                            try {
                                return UUID.fromString(id).toString().equals(id);
                            } catch (IllegalArgumentException malformed) {
                                return false;
                            }
                        })
                .toArray(String[]::new);
    }

    private static boolean isOverlap(DataIntegrityViolationException failure) {
        Throwable cause = failure.getMostSpecificCause();
        return cause.getMessage() != null && cause.getMessage().contains("ex_editorial_list_range");
    }

    private static ListSummary summary(ResultSet rs) throws SQLException {
        OffsetDateTime generated = rs.getObject("generated_at", OffsetDateTime.class);
        return new ListSummary(
                rs.getObject("list_id", UUID.class),
                rs.getObject("range_from", LocalDate.class),
                rs.getObject("range_to", LocalDate.class),
                rs.getString("prompt_version"),
                Optional.ofNullable(rs.getString("generator_model")),
                Optional.ofNullable(generated).map(OffsetDateTime::toInstant),
                rs.getObject("loaded_at", OffsetDateTime.class).toInstant(),
                rs.getInt("entry_count"));
    }

    private static List<String> sources(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return List.of((String[]) array.getArray());
    }

    private OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
