package com.videogameplatform.api.delivery.catalogue.ranking;

import com.videogameplatform.api.delivery.ApiRequestException;
import com.videogameplatform.api.delivery.catalogue.CatalogueCoverMapper;
import com.videogameplatform.api.generated.HomeRankingsApi;
import com.videogameplatform.api.generated.model.GameDetailVisit;
import com.videogameplatform.api.generated.model.HomeRankedGame;
import com.videogameplatform.api.generated.model.HomeRanking;
import com.videogameplatform.api.generated.model.ProblemCode;
import com.videogameplatform.api.generated.model.RatingEligibility;
import com.videogameplatform.catalogue.application.details.GameDetailVisits;
import com.videogameplatform.catalogue.application.details.GameNotFoundException;
import com.videogameplatform.catalogue.application.details.GetGameDetailsUseCase;
import com.videogameplatform.ratings.application.GetRatingContextUseCase;
import com.videogameplatform.ratings.application.RecentRatingCounts;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class HomeRankingsController implements HomeRankingsApi {
    private static final Pattern CRAWLER =
            Pattern.compile("bot\\b|crawler|spider|headless", Pattern.CASE_INSENSITIVE);

    private final RecentRatingCounts votes;
    private final GameDetailVisits visits;
    private final GetGameDetailsUseCase games;
    private final GetRatingContextUseCase ratings;
    private final CatalogueCoverMapper covers;
    private final Clock clock;
    private final HttpServletRequest request;

    HomeRankingsController(
            RecentRatingCounts votes,
            GameDetailVisits visits,
            GetGameDetailsUseCase games,
            GetRatingContextUseCase ratings,
            CatalogueCoverMapper covers,
            Clock clock,
            HttpServletRequest request) {
        this.votes = votes;
        this.visits = visits;
        this.games = games;
        this.ratings = ratings;
        this.covers = covers;
        this.clock = clock;
        this.request = request;
    }

    @Override
    public ResponseEntity<HomeRanking> getHomeRanking(String kind) {
        requireNoQuery();
        var end = clock.instant();
        var start = end.minus(Duration.ofDays(7));
        var entries =
                switch (kind) {
                    case "votes" ->
                            votes.ranking(start, end).stream()
                                    .map(entry -> item(entry.gameId(), entry.count()));
                    case "visits" ->
                            visits.ranking(start, end).stream()
                                    .map(entry -> item(entry.gameId(), entry.count()));
                    default ->
                            throw new ApiRequestException(
                                    ProblemCode.REQUEST_MALFORMED, "/path/kind");
                };
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(new HomeRanking(entries.flatMap(Optional::stream).toList()));
    }

    @Override
    public ResponseEntity<Void> recordGameDetailVisit(GameDetailVisit visit) {
        requireNoQuery();
        var agent = request.getHeader("User-Agent");
        if (agent != null && !CRAWLER.matcher(agent).find()) {
            visits.record(visit.getGameId(), visit.getVisitId());
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private Optional<HomeRankedGame> item(String gameId, long count) {
        try {
            var game = games.get(gameId);
            var rating = ratings.eligibility(game);
            var eligibility =
                    new RatingEligibility(
                            rating.eligible(),
                            RatingEligibility.ReasonEnum.valueOf(rating.reason()),
                            game.evaluatedOn());
            return Optional.of(
                    new HomeRankedGame(
                            game.gameId(),
                            game.slug(),
                            game.canonicalTitle(),
                            covers.toResponse(game.primaryCover()),
                            count,
                            eligibility));
        } catch (GameNotFoundException absent) {
            return Optional.empty();
        }
    }

    private void requireNoQuery() {
        if (request.getQueryString() != null) {
            throw new ApiRequestException(ProblemCode.REQUEST_PARAMETER_UNKNOWN, "/query");
        }
    }
}
