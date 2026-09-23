package com.videogameplatform.api.delivery.catalogue.details;

import com.videogameplatform.api.delivery.ConditionalRequestSupport;
import com.videogameplatform.api.delivery.catalogue.CatalogueCoverMapper;
import com.videogameplatform.api.delivery.catalogue.release.ReleaseApiMapper;
import com.videogameplatform.api.generated.model.AvailableRatingStatistics;
import com.videogameplatform.api.generated.model.EditorialSummary;
import com.videogameplatform.api.generated.model.GameDetails;
import com.videogameplatform.api.generated.model.GameSummaryText;
import com.videogameplatform.api.generated.model.Provenance;
import com.videogameplatform.api.generated.model.RatingDistribution;
import com.videogameplatform.api.generated.model.RatingEligibility;
import com.videogameplatform.api.generated.model.RatingStatistics;
import com.videogameplatform.api.generated.model.SourcedSummary;
import com.videogameplatform.api.generated.model.UnavailableRatingStatistics;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.details.GetGameDetailsUseCase;
import com.videogameplatform.ratings.application.GetRatingContextUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashSet;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** Composes public module results at the HTTP boundary; never accesses personal state. */
@Component
public final class GameDetailsEndpoint {
    private final GetGameDetailsUseCase games;
    private final GetRatingContextUseCase ratings;
    private final CatalogueCoverMapper covers;
    private final ReleaseApiMapper releases;
    private final ConditionalRequestSupport conditional;
    private final MeterRegistry metrics;

    public GameDetailsEndpoint(
            GetGameDetailsUseCase games,
            GetRatingContextUseCase ratings,
            CatalogueCoverMapper covers,
            ReleaseApiMapper releases,
            ConditionalRequestSupport conditional,
            MeterRegistry metrics) {
        this.games = games;
        this.ratings = ratings;
        this.covers = covers;
        this.releases = releases;
        this.conditional = conditional;
        this.metrics = metrics;
    }

    public ResponseEntity<GameDetails> get(String gameId, String ifNoneMatch) {
        var game = games.get(gameId);
        var context = ratings.get(game);
        boolean unavailable =
                context.statistics()
                        instanceof
                        com.videogameplatform.ratings.application.RatingStatistics.Unavailable;
        metrics.counter(
                        "catalogue.game.details",
                        "eligibility",
                        context.reason(),
                        "aggregate",
                        unavailable ? "unavailable" : "available")
                .increment();
        var body =
                new GameDetails(
                        game.gameId(),
                        game.slug(),
                        game.canonicalTitle(),
                        new LinkedHashSet<>(game.aliases()),
                        summary(game.summary()),
                        covers.toResponse(game.primaryCover()),
                        game.releases().stream().map(releases::toRelease).toList(),
                        new RatingEligibility(
                                context.eligible(),
                                RatingEligibility.ReasonEnum.valueOf(context.reason()),
                                context.evaluatedOn()),
                        statistics(context.statistics()));
        String etag = conditional.strongEntityTag(body);
        // Eligibility can change at Madrid midnight; every reuse revalidates the actual response.
        String cache = unavailable ? "no-store" : "public, max-age=0, must-revalidate";
        if (!unavailable && conditional.matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(304).header("Cache-Control", cache).eTag(etag).build();
        }
        return ResponseEntity.ok().header("Cache-Control", cache).eTag(etag).body(body);
    }

    private static GameSummaryText summary(GameDetailsResult.Summary summary) {
        if ("editorial".equals(summary.kind())) {
            return new EditorialSummary("editorial", summary.text(), summary.language());
        }
        var source = summary.provenance();
        return new SourcedSummary(
                "sourced",
                summary.text(),
                summary.language(),
                new Provenance(
                        Provenance.SourceKindEnum.valueOf(source.sourceKind().name()),
                        source.sourceName(),
                        source.sourceEntityType()));
    }

    private static RatingStatistics statistics(
            com.videogameplatform.ratings.application.RatingStatistics statistics) {
        return switch (statistics) {
            case com.videogameplatform.ratings.application.RatingStatistics.Unavailable _ ->
                    new UnavailableRatingStatistics("unavailable", "RATING_STATISTICS_READ_FAILED");
            case com.videogameplatform.ratings.application.RatingStatistics.Available(
                            var mean,
                            var count,
                            var distribution) -> {
                yield new AvailableRatingStatistics(
                        "available",
                        mean,
                        count,
                        new RatingDistribution(
                                distribution.get(0),
                                distribution.get(1),
                                distribution.get(2),
                                distribution.get(3),
                                distribution.get(4),
                                distribution.get(5),
                                distribution.get(6),
                                distribution.get(7),
                                distribution.get(8),
                                distribution.get(9)));
            }
        };
    }
}
