package com.videogameplatform.catalogue.adapter.persistence.details;

import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.details.GameDetailVisits;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

public class JdbcGameDetailVisits implements GameDetailVisits {
    private final NamedParameterJdbcOperations jdbc;
    private final Clock clock;

    public JdbcGameDetailVisits(NamedParameterJdbcOperations jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void record(UUID gameId, UUID visitId) {
        jdbc.update(
                """
                INSERT INTO catalogue.game_detail_visit (visit_id, game_id, occurred_at)
                SELECT :visit, snapshot.game_id, :now
                FROM catalogue.game_snapshot snapshot
                JOIN catalogue.catalogue_publication publication USING (publication_id)
                WHERE publication.is_current AND snapshot.game_id = :game
                ON CONFLICT (visit_id) DO NOTHING
                """,
                Map.of("visit", visitId, "game", gameId, "now", timestamp(clock.instant())));
    }

    @Override
    public List<Count> ranking(Instant start, Instant end) {
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM catalogue.catalogue_publication WHERE is_current)",
                        Map.of(),
                        Boolean.class))) {
            throw new CatalogueNotReadyException();
        }
        return jdbc.query(
                """
                WITH counts AS (
                    SELECT game_id, count(*) AS activity
                    FROM catalogue.game_detail_visit
                    WHERE occurred_at >= :start AND occurred_at < :end
                    GROUP BY game_id
                )
                SELECT counts.game_id, counts.activity
                FROM counts
                JOIN catalogue.game_snapshot snapshot USING (game_id)
                JOIN catalogue.catalogue_publication publication USING (publication_id)
                WHERE publication.is_current
                ORDER BY counts.activity DESC, counts.game_id
                LIMIT 10
                """,
                Map.of("start", timestamp(start), "end", timestamp(end)),
                (row, index) -> new Count(row.getString("game_id"), row.getLong("activity")));
    }

    public void purgeExpiredBatch() {
        jdbc.update(
                """
                WITH expired AS (
                    SELECT visit_id
                    FROM catalogue.game_detail_visit
                    WHERE occurred_at < :cutoff
                    ORDER BY occurred_at, visit_id
                    LIMIT 10000
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM catalogue.game_detail_visit visit
                USING expired
                WHERE visit.visit_id = expired.visit_id
                """,
                Map.of("cutoff", timestamp(clock.instant().minus(Duration.ofDays(8)))));
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
