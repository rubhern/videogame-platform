package com.videogameplatform.catalogue.application.wishlist.port;

import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.wishlist.Wishlist.ExportedWish;
import com.videogameplatform.catalogue.application.wishlist.Wishlist.Placement;
import com.videogameplatform.catalogue.application.wishlist.Wishlist.Timing;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public interface WishlistStore {

    Page list(UUID userId, LocalDate today, long offset, int limit);

    SharedPage listForSharing(UUID userId, long offset, int limit);

    long countForSharing(UUID userId);

    List<String> savedGameIds(UUID userId, List<UUID> gameIds);

    boolean add(UUID userId, UUID gameId, Instant now);

    void remove(UUID userId, UUID gameId);

    boolean move(UUID userId, UUID gameId, UUID anchor, Placement placement);

    void export(UUID userId, Consumer<ExportedWish> consume);

    record Page(List<Row> items, long totalItems) {}

    record SharedPage(List<SharedRow> items, long totalItems) {}

    record SharedRow(String id, String slug, String title, CatalogueCoverReference cover) {}

    record Row(
            String id,
            String slug,
            String title,
            CatalogueCoverReference cover,
            Timing timing,
            ReleaseDate releaseDate) {}
}
