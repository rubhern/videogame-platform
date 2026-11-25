package com.videogameplatform.identity.adapter.persistence;

import com.videogameplatform.identity.application.CurrentUserUnavailableException;
import com.videogameplatform.identity.application.PlayerProfiles;
import com.videogameplatform.identity.application.PlayerProfiles.Rejected.Reason;
import com.videogameplatform.identity.domain.PlayerAlias;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcPlayerProfiles implements PlayerProfiles {
    private static final Duration CHANGE_INTERVAL = Duration.ofDays(30);
    private static final String CURRENT =
            """
            SELECT alias, chosen_at, revision, published, share_ratings, share_wishlist
            FROM identity.player_alias WHERE user_id = :owner
            """;
    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations writes;
    private final Clock clock;

    public JdbcPlayerProfiles(
            NamedParameterJdbcOperations jdbc, TransactionOperations writes, Clock clock) {
        this.jdbc = jdbc;
        this.writes = writes;
        this.clock = clock;
    }

    @Override
    public Optional<Settings> settings(String userId) {
        return find(UUID.fromString(userId), CURRENT);
    }

    @Override
    public Optional<Published> published(String candidate) {
        return PlayerAlias.canonical(candidate)
                .flatMap(
                        alias ->
                                jdbc
                                        .query(
                                                """
                        SELECT a.user_id, a.alias, a.share_ratings, a.share_wishlist
                        FROM identity.player_alias a
                        WHERE a.alias = :alias AND a.published
                          AND NOT EXISTS (SELECT 1 FROM identity.account_deletion d
                                          WHERE d.user_id = a.user_id)
                        """,
                                                Map.of("alias", alias),
                                                (rs, row) ->
                                                        new Published(
                                                                rs.getString("user_id"),
                                                                        rs.getString("alias"),
                                                                rs.getBoolean("share_ratings"),
                                                                        rs.getBoolean(
                                                                                "share_wishlist")))
                                        .stream()
                                        .findFirst());
    }

    @Override
    public Settings save(String userId, Change change) {
        String alias =
                PlayerAlias.canonical(change.alias())
                        .filter(value -> !PlayerAlias.reserved(value))
                        .orElseThrow(() -> new Rejected(Reason.INVALID_ALIAS));
        return Objects.requireNonNull(
                writes.execute(status -> save(UUID.fromString(userId), alias, change)));
    }

    private Settings save(UUID owner, String alias, Change change) {
        // Serialize even two simultaneous claims before this account has an alias row.
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(:owner, 593))",
                Map.of("owner", owner.toString()),
                (rs, row) -> true);
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM identity.account_deletion WHERE user_id = :owner)",
                        Map.of("owner", owner),
                        Boolean.class))) {
            throw new CurrentUserUnavailableException();
        }

        var previous = find(owner, CURRENT + " FOR UPDATE").orElse(null);
        var revision = previous == null ? null : previous.revision();
        if (!Objects.equals(revision, change.revision())) {
            throw new Rejected(Reason.STALE_REVISION);
        }
        boolean sameAlias = previous != null && alias.equals(previous.alias());
        if (sameAlias
                && previous.published() == change.published()
                && previous.shareRatings() == change.shareRatings()
                && previous.shareWishlist() == change.shareWishlist()) {
            return previous;
        }

        var now = clock.instant();
        if (previous != null && !sameAlias) {
            if (now.isBefore(previous.chosenAt().plus(CHANGE_INTERVAL))) {
                throw new Rejected(Reason.CHANGE_TOO_SOON);
            }
            // Avoid waiting on a competing rename of an alias already occupied.
            // The primary key still decides races on a currently free alias.
            if (Boolean.TRUE.equals(
                    jdbc.queryForObject(
                            "SELECT EXISTS (SELECT 1 FROM identity.player_alias WHERE alias = :alias)",
                            Map.of("alias", alias),
                            Boolean.class))) {
                throw new Rejected(Reason.ALIAS_UNAVAILABLE);
            }
            jdbc.queryForObject(
                    "SELECT identity.retire_player_alias(:owner, NULL)",
                    Map.of("owner", owner),
                    Boolean.class);
        }

        var next =
                new Settings(
                        alias,
                        sameAlias ? previous.chosenAt() : now,
                        UUID.randomUUID(),
                        change.published(),
                        change.shareRatings(),
                        change.shareWishlist());
        var values =
                Map.of(
                        "alias",
                        next.alias(),
                        "owner",
                        owner,
                        "chosen",
                        OffsetDateTime.ofInstant(next.chosenAt(), ZoneOffset.UTC),
                        "revision",
                        next.revision(),
                        "published",
                        next.published(),
                        "ratings",
                        next.shareRatings(),
                        "wishlist",
                        next.shareWishlist());
        if (sameAlias) {
            jdbc.update(
                    """
                    UPDATE identity.player_alias SET revision = :revision, published = :published,
                        ever_published = ever_published OR :published, share_ratings = :ratings,
                        share_wishlist = :wishlist
                    WHERE user_id = :owner
                    """,
                    values);
        } else if (jdbc.update(
                        """
                INSERT INTO identity.player_alias
                    (alias, user_id, chosen_at, revision, ever_published, published,
                     share_ratings, share_wishlist)
                VALUES (:alias, :owner, :chosen, :revision, :published, :published, :ratings, :wishlist)
                ON CONFLICT (alias) DO NOTHING
                """,
                        values)
                == 0) {
            // Rolling back also restores the previous alias if this was a rename.
            throw new Rejected(Reason.ALIAS_UNAVAILABLE);
        }
        return next;
    }

    private Optional<Settings> find(UUID owner, String sql) {
        return jdbc.query(sql, Map.of("owner", owner), JdbcPlayerProfiles::settings).stream()
                .findFirst();
    }

    private static Settings settings(ResultSet rs, int row) throws SQLException {
        return new Settings(
                rs.getString("alias"),
                rs.getObject("chosen_at", OffsetDateTime.class).toInstant(),
                rs.getObject("revision", UUID.class),
                rs.getBoolean("published"),
                rs.getBoolean("share_ratings"),
                rs.getBoolean("share_wishlist"));
    }
}
