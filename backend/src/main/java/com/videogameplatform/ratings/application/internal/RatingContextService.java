package com.videogameplatform.ratings.application.internal;

import com.videogameplatform.catalogue.application.CatalogueReleaseDate;
import com.videogameplatform.catalogue.application.CatalogueReleaseStatus;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import com.videogameplatform.ratings.application.GetRatingContextUseCase;
import com.videogameplatform.ratings.application.port.RatingStatisticsReadPort;
import com.videogameplatform.ratings.domain.RatingEligibilityPolicy;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;

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
                                                r.reviewStatus()
                                                        == BrowseReleasesResult.Review.REQUIRED,
                                                r.verificationLevel()
                                                        == BrowseReleasesResult.Verification
                                                                .VERIFIED,
                                                r.releaseDate().precision()
                                                        == CatalogueReleaseDate.Precision.DAY,
                                                periodEnd(r.releaseDate())))
                        .toList();
        return new RatingEligibilityPolicy().evaluate(evidence, game.evaluatedOn());
    }

    private static LocalDate periodEnd(CatalogueReleaseDate date) {
        return switch (date.precision()) {
            case DAY -> LocalDate.parse(date.value());
            case MONTH -> YearMonth.parse(date.value()).atEndOfMonth();
            case QUARTER ->
                    YearMonth.of(
                                    Integer.parseInt(date.value().substring(0, 4)),
                                    Integer.parseInt(date.value().substring(6)) * 3)
                            .atEndOfMonth();
            case YEAR -> LocalDate.of(Integer.parseInt(date.value()), Month.DECEMBER, 31);
            case UNKNOWN -> null;
        };
    }
}
