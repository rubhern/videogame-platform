package com.videogameplatform.ratings.application;

import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import java.time.LocalDate;

/** Consumes only Catalogue's public application context, never its persistence. */
public interface GetRatingContextUseCase {
    Context get(GameDetailsResult game);

    record Context(
            boolean eligible, String reason, LocalDate evaluatedOn, RatingStatistics statistics) {}
}
