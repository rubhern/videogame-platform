package com.videogameplatform.ratings.application.internal;

import com.videogameplatform.catalogue.application.CatalogueReleaseDate;
import com.videogameplatform.catalogue.application.CatalogueReleaseStatus;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import com.videogameplatform.ratings.application.GetRatingContextUseCase;
import com.videogameplatform.ratings.application.port.RatingStatisticsReadPort;
import com.videogameplatform.ratings.domain.RatingEligibilityPolicy;

public final class RatingContextService implements GetRatingContextUseCase {
    private final RatingStatisticsReadPort statistics;

    public RatingContextService(RatingStatisticsReadPort statistics) {
        this.statistics = statistics;
    }

    @Override
    public Context get(GameDetailsResult game) {
        var reason = eligibility(game);
        return new Context(
                reason == RatingEligibilityPolicy.Reason.ELIGIBLE_RELEASE_FOUND,
                reason.name(),
                game.evaluatedOn(),
                statistics.read(game.gameId()));
    }

    static RatingEligibilityPolicy.Reason eligibility(GameDetailsResult game) {
        var evidence =
                game.releases().stream()
                        .map(
                                r ->
                                        new RatingEligibilityPolicy.Evidence(
                                                r.status() == CatalogueReleaseStatus.RELEASED,
                                                r.status() == CatalogueReleaseStatus.CANCELLED,
                                                r.status() == CatalogueReleaseStatus.DELAYED,
                                                r.reviewStatus()
                                                        == BrowseReleasesResult.Review.REQUIRED,
                                                r.verificationLevel()
                                                        == BrowseReleasesResult.Verification
                                                                .VERIFIED,
                                                r.releaseDate().precision()
                                                        == CatalogueReleaseDate.Precision.UNKNOWN))
                        .toList();
        return new RatingEligibilityPolicy().evaluate(evidence);
    }
}
