package com.videogameplatform.catalogue.application.wishlist.internal;

import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.details.GameNotFoundException;
import com.videogameplatform.catalogue.application.wishlist.GameLibrary;
import com.videogameplatform.catalogue.application.wishlist.GameNotReleasedException;
import com.videogameplatform.catalogue.application.wishlist.Wishlist;
import com.videogameplatform.catalogue.application.wishlist.port.GameLibraryStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class GameLibraryService implements GameLibrary {
    // The trusted evaluation zone every catalogue temporal classification uses (REL-011).
    private static final ZoneId EVALUATION_ZONE = ZoneId.of("Europe/Madrid");
    private final GameLibraryStore store;
    private final Wishlist wishlist;
    private final CatalogueCoverPolicy covers;
    private final Clock clock;

    public GameLibraryService(
            GameLibraryStore store, Wishlist wishlist, CatalogueCoverPolicy covers, Clock clock) {
        this.store = store;
        this.wishlist = wishlist;
        this.covers = covers;
        this.clock = clock;
    }

    @Override
    public Page list(String userId, Optional<State> state, int page, int pageSize) {
        var result =
                store.list(
                        UUID.fromString(userId),
                        state.orElse(null),
                        (page - 1L) * pageSize,
                        pageSize,
                        evaluatedOn());
        return new Page(
                result.items().stream()
                        .map(
                                row ->
                                        new Game(
                                                row.id(),
                                                row.slug(),
                                                row.title(),
                                                covers.resolve(row.cover()),
                                                row.state(),
                                                row.stateChangedAt(),
                                                row.released()))
                        .toList(),
                result.counts(),
                result.totalItems(),
                result.ratedWithoutState());
    }

    @Override
    public List<Entry> states(String userId, List<String> gameIds) {
        var ids =
                gameIds.stream()
                        .map(WishlistService::gameUuid)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        return ids.isEmpty() ? List.of() : store.states(UUID.fromString(userId), ids);
    }

    @Override
    public void setState(String userId, String gameId, State state) {
        if (state == State.WISHED) {
            // Entering Deseado shares the wishlist's position allocation.
            wishlist.add(userId, gameId);
            return;
        }
        UUID game = WishlistService.gameUuid(gameId);
        if (game == null) throw new GameNotFoundException();
        var now = clock.instant();
        var change = store.setState(UUID.fromString(userId), game, state, now, evaluatedOn(now));
        if (change == GameLibraryStore.Change.GAME_NOT_FOUND) throw new GameNotFoundException();
        if (change == GameLibraryStore.Change.GAME_NOT_RELEASED)
            throw new GameNotReleasedException();
    }

    private LocalDate evaluatedOn() {
        return evaluatedOn(clock.instant());
    }

    private static LocalDate evaluatedOn(Instant now) {
        return LocalDate.ofInstant(now, EVALUATION_ZONE);
    }

    @Override
    public void remove(String userId, String gameId) {
        UUID game = WishlistService.gameUuid(gameId);
        if (game != null) store.remove(UUID.fromString(userId), game);
    }

    @Override
    public int completeRated(String userId) {
        var now = clock.instant();
        return store.completeRated(
                UUID.fromString(userId), now, evaluatedOn(now), COMPLETION_BATCH);
    }

    @Override
    public void export(String userId, Consumer<ExportedEntry> consume) {
        store.export(UUID.fromString(userId), consume);
    }
}
