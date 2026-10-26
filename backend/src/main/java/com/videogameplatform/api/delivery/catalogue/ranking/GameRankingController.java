package com.videogameplatform.api.delivery.catalogue.ranking;

import com.videogameplatform.api.delivery.ConditionalRequestSupport;
import com.videogameplatform.api.generated.RankingsApi;
import com.videogameplatform.api.generated.model.GameRankingPage;
import com.videogameplatform.api.generated.model.RankingSource;
import com.videogameplatform.catalogue.application.rankings.GameRanking;
import com.videogameplatform.catalogue.application.rankings.RankGamesUseCase;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public HTTP adapter for UC-018 (TOPómetro). */
@RestController
@RequestMapping("/api/v1")
public class GameRankingController implements RankingsApi {

    /**
     * Scores change independently of the catalogue, so a stored page always revalidates before
     * reuse; the validator covers every position and reading.
     */
    static final String CACHE_CONTROL = "public, no-cache";

    private final RankGamesUseCase useCase;
    private final GameRankingApiMapper mapper;
    private final ConditionalRequestSupport conditionalRequests;
    private final GameRankingApiMetrics metrics;

    GameRankingController(
            RankGamesUseCase useCase,
            GameRankingApiMapper mapper,
            ConditionalRequestSupport conditionalRequests,
            GameRankingApiMetrics metrics) {
        this.useCase = useCase;
        this.mapper = mapper;
        this.conditionalRequests = conditionalRequests;
        this.metrics = metrics;
    }

    @Override
    public ResponseEntity<GameRankingPage> rankGames(
            RankingSource source, Integer page, Integer pageSize, String ifNoneMatch) {
        RankingSource requested = source == null ? RankingSource.igdb_players : source;
        GameRanking ranking =
                useCase.rank(
                        new RankGamesUseCase.Query(
                                GameRankingApiMapper.toSource(requested), page, pageSize));
        GameRankingPage body = mapper.toResponse(ranking, requested);
        String entityTag = conditionalRequests.strongEntityTag(body);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
        headers.setETag(entityTag);
        if (conditionalRequests.matches(ifNoneMatch, entityTag)) {
            return ResponseEntity.status(304).headers(headers).build();
        }

        metrics.recordPage(requested.getValue(), body.getItems().size());
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
