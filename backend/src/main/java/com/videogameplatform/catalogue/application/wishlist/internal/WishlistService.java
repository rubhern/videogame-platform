package com.videogameplatform.catalogue.application.wishlist.internal;

import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.details.GameNotFoundException;
import com.videogameplatform.catalogue.application.internal.CatalogueReadMapping;
import com.videogameplatform.catalogue.application.wishlist.Wishlist;
import com.videogameplatform.catalogue.application.wishlist.WishlistGameNotFoundException;
import com.videogameplatform.catalogue.application.wishlist.port.WishlistStore;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public final class WishlistService implements Wishlist {
    private final WishlistStore store;
    private final CatalogueCoverPolicy covers;
    private final Clock clock;

    public WishlistService(WishlistStore store, CatalogueCoverPolicy covers, Clock clock) {
        this.store = store;
        this.covers = covers;
        this.clock = clock.withZone(ZoneId.of("Europe/Madrid"));
    }

    @Override
    public Page list(String userId, int page, int pageSize) {
        var result =
                store.list(
                        UUID.fromString(userId),
                        LocalDate.now(clock),
                        (page - 1L) * pageSize,
                        pageSize);
        return new Page(
                result.items().stream()
                        .map(
                                row ->
                                        new Game(
                                                row.id(),
                                                row.slug(),
                                                row.title(),
                                                covers.resolve(row.cover()),
                                                row.timing(),
                                                CatalogueReadMapping.toReleaseDate(
                                                        row.releaseDate())))
                        .toList(),
                result.totalItems());
    }

    @Override
    public List<String> savedGameIds(String userId, List<String> gameIds) {
        var ids =
                gameIds.stream()
                        .map(WishlistService::gameUuid)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        return ids.isEmpty() ? List.of() : store.savedGameIds(UUID.fromString(userId), ids);
    }

    @Override
    public void add(String userId, String gameId) {
        UUID game = gameUuid(gameId);
        if (game == null || !store.add(UUID.fromString(userId), game, clock.instant())) {
            throw new GameNotFoundException();
        }
    }

    @Override
    public void remove(String userId, String gameId) {
        UUID game = gameUuid(gameId);
        if (game != null) store.remove(UUID.fromString(userId), game);
    }

    @Override
    public void move(String userId, String gameId, String anchorGameId, Placement placement) {
        UUID game = gameUuid(gameId);
        UUID anchor = gameUuid(anchorGameId);
        if (game == null
                || anchor == null
                || !store.move(UUID.fromString(userId), game, anchor, placement)) {
            throw new WishlistGameNotFoundException();
        }
    }

    @Override
    public void export(String userId, Consumer<ExportedWish> consume) {
        store.export(UUID.fromString(userId), consume);
    }

    private static UUID gameUuid(String value) {
        if (value.length() != 36) return null;
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equals(value) ? id : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
