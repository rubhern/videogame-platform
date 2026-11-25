package com.videogameplatform.catalogue.adapter.persistence.wishlist;

import com.videogameplatform.catalogue.adapter.persistence.CatalogueCoverReferenceRowMapper;
import com.videogameplatform.catalogue.adapter.persistence.CatalogueReadTransaction;
import com.videogameplatform.catalogue.adapter.persistence.ReleaseDateRowMapper;
import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.wishlist.Wishlist.ExportedWish;
import com.videogameplatform.catalogue.application.wishlist.Wishlist.Placement;
import com.videogameplatform.catalogue.application.wishlist.Wishlist.Timing;
import com.videogameplatform.catalogue.application.wishlist.port.WishlistStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcWishlistStore implements WishlistStore {
    private static final int EXPORT_LIMIT = 10_000;
    private static final String CURRENT_PUBLICATION =
            "(SELECT publication_id FROM catalogue.catalogue_publication WHERE is_current)";
    private static final String OWNER =
            "FROM catalogue.game_library_entry w WHERE w.user_id = :owner AND w.state = 'wished'";
    private static final String SAVED_PAGE =
            """
            WITH page AS MATERIALIZED (
                SELECT game_id, position_key FROM catalogue.game_library_entry
                WHERE user_id = :owner AND state = 'wished'
                ORDER BY position_key, game_id
                LIMIT :limit OFFSET :offset
            )
            """;
    private static final String LIST =
            SAVED_PAGE
                    + """
            SELECT w.game_id::text, gs.slug, gs.canonical_title, gs.cover_alternative_text,
                   gs.cover_usage_mode, gs.cover_reference, gs.cover_source, gs.cover_source_url,
                   chosen.timing,
                   coalesce(chosen.date_precision, 'unknown') AS date_precision,
                   chosen.exact_date, chosen.release_year, chosen.release_month, chosen.release_quarter
            FROM page w
            LEFT JOIN catalogue.game_snapshot gs
              ON gs.publication_id = %s AND gs.game_id = w.game_id
            LEFT JOIN LATERAL (
                SELECT r.*, CASE WHEN r.period_end >= :today THEN 0 ELSE 1 END AS timing
                FROM catalogue.release_snapshot r
                WHERE r.publication_id = gs.publication_id AND r.game_id = w.game_id
                  AND r.period_start IS NOT NULL AND r.release_status NOT IN ('cancelled', 'delayed')
                ORDER BY timing,
                         CASE WHEN r.period_end >= :today THEN r.period_start END,
                         CASE WHEN r.period_end < :today THEN r.period_end END DESC,
                         r.release_id
                LIMIT 1
            ) chosen ON true
            ORDER BY w.position_key, w.game_id
            """
                            .formatted(CURRENT_PUBLICATION);
    private static final String SHARED_LIST =
            SAVED_PAGE
                    + """
            SELECT w.game_id::text, gs.slug, gs.canonical_title, gs.cover_alternative_text,
                   gs.cover_usage_mode, gs.cover_reference, gs.cover_source, gs.cover_source_url
            FROM page w
            LEFT JOIN catalogue.game_snapshot gs
                ON gs.publication_id = %s AND gs.game_id = w.game_id
            ORDER BY w.position_key, w.game_id
            """
                            .formatted(CURRENT_PUBLICATION);
    private static final String ADD =
            """
            WITH target AS (
                SELECT game_id FROM catalogue.game_snapshot
                WHERE publication_id = %s AND game_id = :game
            ), existing AS (
                SELECT game_id FROM catalogue.game_library_entry
                WHERE user_id = :owner AND game_id = :game
            ), saved AS (
                -- A new wish needs a published game; an entry in another state becomes wished.
                INSERT INTO catalogue.game_library_entry
                    (user_id, game_id, state, state_changed_at, wished_at, position_key)
                SELECT :owner, game_id, 'wished', :now, :now,
                       coalesce((SELECT ceil(position_key) FROM catalogue.game_library_entry
                           WHERE user_id = :owner AND position_key IS NOT NULL
                           ORDER BY position_key DESC LIMIT 1), 0) + 1
                FROM (SELECT game_id FROM target UNION SELECT game_id FROM existing) candidate
                ON CONFLICT (user_id, game_id) DO UPDATE
                    SET state = 'wished', state_changed_at = EXCLUDED.state_changed_at,
                        wished_at = EXCLUDED.wished_at, position_key = EXCLUDED.position_key
                    WHERE game_library_entry.state <> 'wished'
            )
            SELECT EXISTS (SELECT 1 FROM target) OR EXISTS (SELECT 1 FROM existing)
            """
                    .formatted(CURRENT_PUBLICATION);
    private static final String OWNER_ROWS =
            "FROM catalogue.game_library_entry WHERE user_id = :owner AND state = 'wished'";
    private static final String EXPORT =
            """
            WITH bounded_wishes AS (
                SELECT game_id, wished_at, position_key
                FROM catalogue.game_library_entry
                WHERE user_id = :owner AND state = 'wished'
                ORDER BY game_id
                LIMIT :limit
            )
            SELECT w.game_id::text, gs.canonical_title, w.wished_at,
                   row_number() OVER (ORDER BY w.position_key, w.game_id) AS position
            FROM bounded_wishes w
            LEFT JOIN catalogue.game_snapshot gs
                ON gs.publication_id = %s AND gs.game_id = w.game_id
            ORDER BY w.game_id
            """
                    .formatted(CURRENT_PUBLICATION);

    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations reads;
    private final TransactionOperations writes;

    public JdbcWishlistStore(
            NamedParameterJdbcOperations jdbc,
            TransactionOperations reads,
            TransactionOperations writes) {
        this.jdbc = jdbc;
        this.reads = reads;
        this.writes = writes;
    }

    @Override
    public Page list(UUID userId, LocalDate today, long offset, int limit) {
        return CatalogueReadTransaction.execute(
                reads,
                status -> {
                    long total = count(userId);
                    if (offset >= total) return new Page(List.of(), total);
                    var rows =
                            jdbc.query(
                                    LIST,
                                    Map.of(
                                            "owner", userId, "today", today, "offset", offset,
                                            "limit", limit),
                                    (rs, index) -> {
                                        var timing = rs.getObject("timing", Integer.class);
                                        String slug = rs.getString("slug");
                                        return new Row(
                                                rs.getString(1),
                                                slug,
                                                rs.getString("canonical_title"),
                                                slug == null
                                                        ? new CatalogueCoverReference.Unavailable()
                                                        : CatalogueCoverReferenceRowMapper.map(rs),
                                                timing == null
                                                        ? Timing.UNDATED
                                                        : timing == 0
                                                                ? Timing.UPCOMING
                                                                : Timing.RELEASED,
                                                ReleaseDateRowMapper.map(rs));
                                    });
                    return new Page(rows, total);
                });
    }

    @Override
    public SharedPage listForSharing(UUID userId, long offset, int limit) {
        return CatalogueReadTransaction.execute(
                reads,
                status -> {
                    long total = count(userId);
                    if (offset >= total) return new SharedPage(List.of(), total);
                    var rows =
                            jdbc.query(
                                    SHARED_LIST,
                                    Map.of("owner", userId, "offset", offset, "limit", limit),
                                    (rs, index) -> {
                                        String slug = rs.getString("slug");
                                        return new SharedRow(
                                                rs.getString(1),
                                                slug,
                                                rs.getString("canonical_title"),
                                                slug == null
                                                        ? new CatalogueCoverReference.Unavailable()
                                                        : CatalogueCoverReferenceRowMapper.map(rs));
                                    });
                    return new SharedPage(rows, total);
                });
    }

    @Override
    public long countForSharing(UUID userId) {
        return CatalogueReadTransaction.execute(reads, status -> count(userId));
    }

    private long count(UUID owner) {
        return jdbc.queryForObject("SELECT count(*) " + OWNER, Map.of("owner", owner), Long.class);
    }

    @Override
    public List<String> savedGameIds(UUID userId, List<UUID> gameIds) {
        return jdbc.query(
                "SELECT w.game_id::text " + OWNER + " AND w.game_id IN (:ids) ORDER BY w.game_id",
                Map.of("owner", userId, "ids", gameIds),
                (rs, index) -> rs.getString(1));
    }

    @Override
    public boolean add(UUID userId, UUID gameId, Instant now) {
        return Boolean.TRUE.equals(
                writes.execute(
                        transaction -> {
                            lockOrder(userId);
                            return jdbc.queryForObject(
                                    ADD,
                                    Map.of(
                                            "owner",
                                            userId,
                                            "game",
                                            gameId,
                                            "now",
                                            now.atOffset(ZoneOffset.UTC)),
                                    Boolean.class);
                        }));
    }

    @Override
    public void remove(UUID userId, UUID gameId) {
        writes.executeWithoutResult(
                transaction -> {
                    lockOrder(userId);
                    jdbc.update(
                            "DELETE FROM catalogue.game_library_entry WHERE user_id = :owner AND game_id = :game AND state = 'wished'",
                            Map.of("owner", userId, "game", gameId));
                });
    }

    @Override
    public boolean move(UUID userId, UUID gameId, UUID anchor, Placement placement) {
        return Boolean.TRUE.equals(
                writes.execute(
                        transaction -> {
                            lockOrder(userId);
                            var keys =
                                    jdbc.query(
                                            "SELECT game_id, position_key "
                                                    + OWNER_ROWS
                                                    + " AND game_id IN (:game, :anchor)",
                                            Map.of(
                                                    "owner", userId, "game", gameId, "anchor",
                                                    anchor),
                                            rs -> {
                                                var positions =
                                                        new java.util.HashMap<UUID, BigDecimal>();
                                                while (rs.next())
                                                    positions.put(
                                                            rs.getObject(1, UUID.class),
                                                            rs.getBigDecimal(2));
                                                return positions;
                                            });
                            if (!keys.containsKey(gameId) || !keys.containsKey(anchor))
                                return false;
                            if (gameId.equals(anchor)) return true;

                            boolean before = placement == Placement.BEFORE;
                            BigDecimal reference = keys.get(anchor);
                            String direction =
                                    before
                                            ? "< :reference ORDER BY position_key DESC"
                                            : "> :reference ORDER BY position_key";
                            var neighbor =
                                    jdbc.query(
                                            "SELECT position_key "
                                                    + OWNER_ROWS
                                                    + " AND game_id <> :game AND position_key "
                                                    + direction
                                                    + " LIMIT 1",
                                            Map.of(
                                                    "owner",
                                                    userId,
                                                    "game",
                                                    gameId,
                                                    "reference",
                                                    reference),
                                            (rs, row) -> rs.getBigDecimal(1));
                            BigDecimal next =
                                    neighbor.isEmpty()
                                            ? reference.add(
                                                    before
                                                            ? BigDecimal.ONE.negate()
                                                            : BigDecimal.ONE)
                                            : reference
                                                    .add(neighbor.getFirst())
                                                    .multiply(new BigDecimal("0.5"))
                                                    .stripTrailingZeros();
                            jdbc.update(
                                    "UPDATE catalogue.game_library_entry SET position_key = :position WHERE user_id = :owner AND game_id = :game",
                                    Map.of("owner", userId, "game", gameId, "position", next));
                            return true;
                        }));
    }

    private void lockOrder(UUID userId) {
        lockLibrary(jdbc, userId);
    }

    /** Serializes one owner's library changes across BFF instances. */
    static void lockLibrary(NamedParameterJdbcOperations jdbc, UUID userId) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:owner AS text), 590))",
                Map.of("owner", userId),
                rs -> null);
    }

    @Override
    public void export(UUID userId, Consumer<ExportedWish> consume) {
        jdbc.query(
                EXPORT,
                Map.of("owner", userId, "limit", EXPORT_LIMIT + 1),
                rs -> {
                    int count = 0;
                    while (rs.next()) {
                        if (++count > EXPORT_LIMIT)
                            throw new IllegalStateException("Wishlist export budget exceeded");
                        consume.accept(
                                new ExportedWish(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getObject(3, OffsetDateTime.class).toInstant(),
                                        rs.getLong(4)));
                    }
                    return null;
                });
    }
}
