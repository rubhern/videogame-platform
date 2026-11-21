package com.videogameplatform.catalogue.application.wishlist;

import com.videogameplatform.catalogue.application.CatalogueReleaseDate;
import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

public interface Wishlist {

    Page list(String userId, int page, int pageSize);

    List<String> savedGameIds(String userId, List<String> gameIds);

    void add(String userId, String gameId);

    void remove(String userId, String gameId);

    void move(String userId, String gameId, String anchorGameId, Placement placement);

    void export(String userId, Consumer<ExportedWish> consume);

    record Page(List<Game> items, long totalItems) {}

    record Game(
            String id,
            String slug,
            String title,
            CatalogueCover cover,
            Timing timing,
            CatalogueReleaseDate releaseDate) {}

    enum Placement {
        BEFORE,
        AFTER
    }

    enum Timing {
        UPCOMING,
        RELEASED,
        UNDATED
    }

    record ExportedWish(String gameId, String title, Instant addedAt, long position) {}
}
