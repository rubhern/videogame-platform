package com.videogameplatform.ratings.application;

import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import java.time.LocalDate;

/** Consumes only Catalogue's public application context, never its persistence. */
public interface GetRatingContextUseCase {
    Context get(GameDetailsResult game);

    Eligibility eligibility(GameDetailsResult game);

    record Eligibility(boolean eligible, String reason) {}

    record Context(
            boolean eligible, String reason, LocalDate evaluatedOn, RatingStatistics statistics) {}
}
