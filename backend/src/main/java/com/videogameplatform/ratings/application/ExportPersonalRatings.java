package com.videogameplatform.ratings.application;

import java.util.function.Consumer;

/** Full supported owner projection; batches and a hard export budget prevent unbounded work. */
public interface ExportPersonalRatings {
    void read(String userId, Consumer<Item> consume);

    record Item(String gameId, String title, PersonalRating rating) {}
}
