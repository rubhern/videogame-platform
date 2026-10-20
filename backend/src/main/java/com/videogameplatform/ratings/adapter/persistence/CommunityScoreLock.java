package com.videogameplatform.ratings.adapter.persistence;

import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

/**
 * Per-game transaction lock around a game's community aggregate. A write and the startup backfill
 * read the aggregate only while holding it, so under READ COMMITTED each sees every committed
 * rating and the last projection written is the current one. It is released at commit or
 * rollback and never held across games by a rating write.
 */
final class CommunityScoreLock {

    /** Keeps this lock apart from the game-listing projection lock on the same game id. */
    private static final long NAMESPACE = 285;

    private CommunityScoreLock() {}

    static void acquire(NamedParameterJdbcOperations jdbc, String gameId) {
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(:game, :namespace))",
                Map.of("game", gameId, "namespace", NAMESPACE));
    }
}
