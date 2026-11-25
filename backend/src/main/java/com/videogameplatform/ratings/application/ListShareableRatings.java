package com.videogameplatform.ratings.application;

import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import java.math.BigDecimal;
import java.util.List;

/** A bounded projection for an authorized public profile. */
public interface ListShareableRatings {
    Page listForSharing(String userId, Order order, int page, int pageSize);

    Statistics statisticsForSharing(String userId);

    enum Order {
        RATING_DATE,
        SCORE,
        RELEASE_DATE,
        INTEREST
    }

    record Statistics(
            long total,
            BigDecimal mean,
            BigDecimal lowest,
            BigDecimal highest,
            List<Long> distribution) {}

    record Page(List<Item> items, long totalItems) {}

    record Item(String gameId, String slug, String title, CatalogueCover cover, BigDecimal score) {}
}
