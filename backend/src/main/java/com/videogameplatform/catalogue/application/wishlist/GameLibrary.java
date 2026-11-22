package com.videogameplatform.catalogue.application.wishlist;

import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** The current user's private game library: one exclusive state per game (UC-026). */
public interface GameLibrary {

    Page list(String userId, Optional<State> state, int page, int pageSize);

    List<Entry> states(String userId, List<String> gameIds);

    /**
     * Idempotent. Deseado follows the wishlist; every other state needs a game in the current
     * publication with a release that has occurred by the trusted evaluation date.
     *
     * @throws GameNotReleasedException when no release of the game has occurred
     */
    void setState(String userId, String gameId, State state);

    void remove(String userId, String gameId);

    /** The most rated games one {@link #completeRated} call marks. */
    int COMPLETION_BATCH = 1_000;

    /**
     * Marks Completado, in one bounded batch, the rated games that hold no state and could take
     * one (published, with a release that has occurred).
     *
     * @return how many games were marked
     */
    int completeRated(String userId);

    void export(String userId, Consumer<ExportedEntry> consume);

    enum State {
        WISHED,
        BACKLOG,
        PLAYING,
        COMPLETED,
        ABANDONED
    }

    /**
     * @param ratedWithoutState rated games with no state that {@link #completeRated} would mark
     */
    record Page(List<Game> items, Counts counts, long totalItems, long ratedWithoutState) {}

    record Counts(long wished, long backlog, long playing, long completed, long abandoned) {
        public long total() {
            return wished + backlog + playing + completed + abandoned;
        }
    }

    record Game(
            String id,
            String slug,
            String title,
            CatalogueCover cover,
            State state,
            Instant stateChangedAt,
            boolean released) {}

    record Entry(String gameId, State state) {}

    record ExportedEntry(String gameId, String title, State state, Instant stateChangedAt) {}
}
