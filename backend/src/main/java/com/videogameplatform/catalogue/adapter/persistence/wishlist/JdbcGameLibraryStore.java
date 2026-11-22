package com.videogameplatform.catalogue.adapter.persistence.wishlist;

import com.videogameplatform.catalogue.adapter.persistence.CatalogueCoverReferenceRowMapper;
import com.videogameplatform.catalogue.adapter.persistence.CatalogueReadTransaction;
import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.wishlist.GameLibrary.Counts;
import com.videogameplatform.catalogue.application.wishlist.GameLibrary.Entry;
import com.videogameplatform.catalogue.application.wishlist.GameLibrary.ExportedEntry;
import com.videogameplatform.catalogue.application.wishlist.GameLibrary.State;
import com.videogameplatform.catalogue.application.wishlist.port.GameLibraryStore;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL library states on the same entry the wishlist uses (ADR-0050, #591). */
public final class JdbcGameLibraryStore implements GameLibraryStore {
    private static final int EXPORT_LIMIT = 10_000;
    // Named SQL parameters shared by the statements below.
    private static final String OWNER = "owner";
    private static final String STATE = "state";
    private static final String LIMIT = "limit";
    private static final String EVALUATED_ON = "evaluatedOn";
    private static final String CURRENT_PUBLICATION =
            "(SELECT publication_id FROM catalogue.catalogue_publication WHERE is_current)";
    // A release of the game in the current publication that has occurred by the evaluation date,
    // the SQL form of EffectiveReleaseStatusPolicy's RELEASED: neither cancelled nor delayed, and
    // an exact day reached, a month, quarter or year ended, or an unknown date with explicit
    // released evidence. ix_release_snapshot_publication_game_period bounds it to one game.
    private static final String RELEASED =
            """
            EXISTS (
                SELECT 1 FROM catalogue.release_snapshot rs
                WHERE rs.publication_id = %s AND rs.game_id = %s
                  AND rs.release_status NOT IN ('cancelled', 'delayed')
                  AND ((rs.date_precision = 'day' AND rs.period_end <= CAST(:evaluatedOn AS date))
                    OR (rs.date_precision IN ('month', 'quarter', 'year')
                        AND rs.period_end < CAST(:evaluatedOn AS date))
                    OR (rs.date_precision = 'unknown' AND rs.release_status = 'released')))
            """;
    // Rated games (Ratings' projection) without an entry that could take a state: published, with
    // a release that has occurred. The count and the completion share it.
    private static final String RATED_WITHOUT_STATE =
            """
            FROM catalogue.library_rated_game r
            JOIN catalogue.game_snapshot gs
              ON gs.publication_id = %s AND gs.game_id = r.game_id
            WHERE r.user_id = :owner
              AND NOT EXISTS (
                  SELECT 1 FROM catalogue.game_library_entry e
                  WHERE e.user_id = r.user_id AND e.game_id = r.game_id)
              AND %s
            """;
    private static final String COUNTS =
            """
            SELECT state, count(*) FROM catalogue.game_library_entry
            WHERE user_id = :owner GROUP BY state
            """;
    private static final String LIST =
            """
            WITH page AS MATERIALIZED (
                SELECT game_id, state, state_changed_at FROM catalogue.game_library_entry
                WHERE user_id = :owner %s
                ORDER BY state_changed_at DESC, game_id
                LIMIT :limit OFFSET :offset
            )
            SELECT p.game_id::text, p.state, p.state_changed_at, gs.slug, gs.canonical_title,
                   gs.cover_alternative_text, gs.cover_usage_mode, gs.cover_reference,
                   gs.cover_source, gs.cover_source_url,
                   gs.game_id IS NOT NULL AND %s AS released
            FROM page p
            LEFT JOIN catalogue.game_snapshot gs
              ON gs.publication_id = %s AND gs.game_id = p.game_id
            ORDER BY p.state_changed_at DESC, p.game_id
            """;
    // Separate statements keep the state filter on its index even under generic prepared plans.
    private static final String LIST_RELEASED =
            RELEASED.formatted("gs.publication_id", "p.game_id");
    private static final String LIST_ALL = LIST.formatted("", LIST_RELEASED, CURRENT_PUBLICATION);
    private static final String LIST_STATE =
            LIST.formatted("AND state = :state", LIST_RELEASED, CURRENT_PUBLICATION);
    private static final String GAME_CHECK =
            """
            SELECT EXISTS (
                       SELECT 1 FROM catalogue.game_snapshot
                       WHERE publication_id = %1$s AND game_id = :game),
                   %2$s
            """
                    .formatted(
                            CURRENT_PUBLICATION, RELEASED.formatted(CURRENT_PUBLICATION, ":game"));
    private static final String RATED_SCOPE =
            RATED_WITHOUT_STATE.formatted(
                    CURRENT_PUBLICATION, RELEASED.formatted("gs.publication_id", "r.game_id"));
    private static final String COUNT_RATED = "SELECT count(*) " + RATED_SCOPE;
    private static final String COMPLETE_RATED =
            """
            INSERT INTO catalogue.game_library_entry (user_id, game_id, state, state_changed_at)
            SELECT :owner, chosen.game_id, 'completed', :now
            FROM (SELECT r.game_id %s ORDER BY r.game_id LIMIT :limit) chosen
            ON CONFLICT (user_id, game_id) DO NOTHING
            """
                    .formatted(RATED_SCOPE);
    private static final String CHANGE =
            """
            UPDATE catalogue.game_library_entry
            SET state = :state, state_changed_at = :now,
                wished_at = NULL, position_key = NULL, priority = NULL
            WHERE user_id = :owner AND game_id = :game AND state <> :state
            """;
    private static final String CREATE =
            """
            INSERT INTO catalogue.game_library_entry (user_id, game_id, state, state_changed_at)
            SELECT :owner, game_id, :state, :now FROM catalogue.game_snapshot
            WHERE publication_id = %s AND game_id = :game
            ON CONFLICT (user_id, game_id) DO NOTHING
            """
                    .formatted(CURRENT_PUBLICATION);
    private static final String EXPORT =
            """
            WITH bounded AS (
                SELECT game_id, state, state_changed_at
                FROM catalogue.game_library_entry
                WHERE user_id = :owner
                ORDER BY game_id
                LIMIT :limit
            )
            SELECT b.game_id::text, gs.canonical_title, b.state, b.state_changed_at
            FROM bounded b
            LEFT JOIN catalogue.game_snapshot gs
                ON gs.publication_id = %s AND gs.game_id = b.game_id
            ORDER BY b.game_id
            """
                    .formatted(CURRENT_PUBLICATION);

    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations reads;
    private final TransactionOperations writes;

    public JdbcGameLibraryStore(
            NamedParameterJdbcOperations jdbc,
            TransactionOperations reads,
            TransactionOperations writes) {
        this.jdbc = jdbc;
        this.reads = reads;
        this.writes = writes;
    }

    @Override
    public Page list(UUID userId, State state, long offset, int limit, LocalDate evaluatedOn) {
        return CatalogueReadTransaction.execute(
                reads,
                status -> {
                    var perState = new EnumMap<State, Long>(State.class);
                    jdbc.query(
                            COUNTS,
                            Map.of(OWNER, userId),
                            rs -> {
                                perState.put(state(rs.getString(1)), rs.getLong(2));
                            });
                    var counts =
                            new Counts(
                                    perState.getOrDefault(State.WISHED, 0L),
                                    perState.getOrDefault(State.BACKLOG, 0L),
                                    perState.getOrDefault(State.PLAYING, 0L),
                                    perState.getOrDefault(State.COMPLETED, 0L),
                                    perState.getOrDefault(State.ABANDONED, 0L));
                    long total = state == null ? counts.total() : perState.getOrDefault(state, 0L);
                    Long rated =
                            jdbc.queryForObject(
                                    COUNT_RATED,
                                    Map.of(OWNER, userId, EVALUATED_ON, evaluatedOn),
                                    Long.class);
                    long ratedWithoutState = rated == null ? 0 : rated;
                    if (offset >= total)
                        return new Page(List.of(), counts, total, ratedWithoutState);
                    var parameters = new HashMap<String, Object>();
                    parameters.put(OWNER, userId);
                    if (state != null) parameters.put(STATE, value(state));
                    parameters.put("offset", offset);
                    parameters.put(LIMIT, limit);
                    parameters.put(EVALUATED_ON, evaluatedOn);
                    var rows =
                            jdbc.query(
                                    state == null ? LIST_ALL : LIST_STATE,
                                    parameters,
                                    (rs, index) -> {
                                        String slug = rs.getString("slug");
                                        return new Row(
                                                rs.getString(1),
                                                slug,
                                                rs.getString("canonical_title"),
                                                slug == null
                                                        ? new CatalogueCoverReference.Unavailable()
                                                        : CatalogueCoverReferenceRowMapper.map(rs),
                                                state(rs.getString("state")),
                                                rs.getObject(
                                                                "state_changed_at",
                                                                OffsetDateTime.class)
                                                        .toInstant(),
                                                rs.getBoolean("released"));
                                    });
                    return new Page(rows, counts, total, ratedWithoutState);
                });
    }

    @Override
    public List<Entry> states(UUID userId, List<UUID> gameIds) {
        return jdbc.query(
                """
                SELECT game_id::text, state FROM catalogue.game_library_entry
                WHERE user_id = :owner AND game_id IN (:ids) ORDER BY game_id
                """,
                Map.of(OWNER, userId, "ids", gameIds),
                (rs, index) -> new Entry(rs.getString(1), state(rs.getString(2))));
    }

    @Override
    public Change setState(
            UUID userId, UUID gameId, State state, Instant now, LocalDate evaluatedOn) {
        if (state == State.WISHED)
            throw new IllegalArgumentException("Wishes keep their list position");
        return writes.execute(
                transaction -> {
                    JdbcWishlistStore.lockLibrary(jdbc, userId);
                    var parameters =
                            Map.<String, Object>of(
                                    OWNER,
                                    userId,
                                    "game",
                                    gameId,
                                    STATE,
                                    value(state),
                                    "now",
                                    now.atOffset(ZoneOffset.UTC),
                                    EVALUATED_ON,
                                    evaluatedOn);
                    // Read under the owner's lock, in the same transaction as the write.
                    Change check =
                            jdbc.query(
                                    GAME_CHECK,
                                    parameters,
                                    rs -> {
                                        rs.next();
                                        if (!rs.getBoolean(1)) return Change.GAME_NOT_FOUND;
                                        return rs.getBoolean(2)
                                                ? Change.APPLIED
                                                : Change.GAME_NOT_RELEASED;
                                    });
                    if (check != Change.APPLIED) return check;
                    // CHANGE moves an existing entry; CREATE inserts a new one for the published
                    // game, so exactly one of them writes unless the state is already current.
                    if (jdbc.update(CHANGE, parameters) == 0) jdbc.update(CREATE, parameters);
                    return Change.APPLIED;
                });
    }

    @Override
    public void remove(UUID userId, UUID gameId) {
        writes.executeWithoutResult(
                transaction -> {
                    JdbcWishlistStore.lockLibrary(jdbc, userId);
                    jdbc.update(
                            "DELETE FROM catalogue.game_library_entry WHERE user_id = :owner AND game_id = :game",
                            Map.of(OWNER, userId, "game", gameId));
                });
    }

    @Override
    public int completeRated(UUID userId, Instant now, LocalDate evaluatedOn, int limit) {
        return Optional.ofNullable(
                        writes.execute(
                                transaction -> {
                                    JdbcWishlistStore.lockLibrary(jdbc, userId);
                                    return jdbc.update(
                                            COMPLETE_RATED,
                                            Map.of(
                                                    OWNER,
                                                    userId,
                                                    "now",
                                                    now.atOffset(ZoneOffset.UTC),
                                                    EVALUATED_ON,
                                                    evaluatedOn,
                                                    LIMIT,
                                                    limit));
                                }))
                // The update count is never null; the transaction template only declares it so.
                .orElse(0);
    }

    @Override
    public void export(UUID userId, Consumer<ExportedEntry> consume) {
        jdbc.query(
                EXPORT,
                Map.of(OWNER, userId, LIMIT, EXPORT_LIMIT + 1),
                rs -> {
                    int count = 0;
                    while (rs.next()) {
                        if (++count > EXPORT_LIMIT)
                            throw new IllegalStateException("Library export budget exceeded");
                        consume.accept(
                                new ExportedEntry(
                                        rs.getString(1),
                                        rs.getString(2),
                                        state(rs.getString(3)),
                                        rs.getObject(4, OffsetDateTime.class).toInstant()));
                    }
                    return null;
                });
    }

    private static String value(State state) {
        return state.name().toLowerCase(Locale.ROOT);
    }

    private static State state(String value) {
        return State.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
