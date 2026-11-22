package com.videogameplatform.catalogue.application.wishlist.port;

import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.wishlist.GameLibrary.Counts;
import com.videogameplatform.catalogue.application.wishlist.GameLibrary.Entry;
import com.videogameplatform.catalogue.application.wishlist.GameLibrary.ExportedEntry;
import com.videogameplatform.catalogue.application.wishlist.GameLibrary.State;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public interface GameLibraryStore {

    /**
     * Counts every state; the page holds only {@code state} when it is not null. Each row says
     * whether its game has a release that has occurred by {@code evaluatedOn}.
     */
    Page list(UUID userId, State state, long offset, int limit, LocalDate evaluatedOn);

    List<Entry> states(UUID userId, List<UUID> gameIds);

    /**
     * Moves an entry to a state other than wished, or creates it, for a published game with a
     * release that has occurred by {@code evaluatedOn}.
     */
    Change setState(UUID userId, UUID gameId, State state, Instant now, LocalDate evaluatedOn);

    enum Change {
        APPLIED,
        GAME_NOT_FOUND,
        GAME_NOT_RELEASED
    }

    void remove(UUID userId, UUID gameId);

    /** Marks up to {@code limit} rated games without state Completado, in game ID order. */
    int completeRated(UUID userId, Instant now, LocalDate evaluatedOn, int limit);

    void export(UUID userId, Consumer<ExportedEntry> consume);

    record Page(List<Row> items, Counts counts, long totalItems, long ratedWithoutState) {}

    record Row(
            String id,
            String slug,
            String title,
            CatalogueCoverReference cover,
            State state,
            Instant stateChangedAt,
            boolean released) {}
}
