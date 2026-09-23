package com.videogameplatform.ratings.application;

import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import java.util.List;

public record PersonalRatingsPage(List<Item> items, int page, int pageSize, long totalItems) {
    public PersonalRatingsPage {
        items = List.copyOf(items);
    }

    public long totalPages() {
        return totalItems / pageSize + (totalItems % pageSize == 0 ? 0 : 1);
    }

    public record Item(
            String gameId,
            String slug,
            String canonicalTitle,
            CatalogueCover cover,
            PersonalRating rating) {}
}
