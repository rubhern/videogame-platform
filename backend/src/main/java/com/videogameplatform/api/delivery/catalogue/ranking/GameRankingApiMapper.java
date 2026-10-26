package com.videogameplatform.api.delivery.catalogue.ranking;

import com.videogameplatform.api.delivery.catalogue.CatalogueCoverMapper;
import com.videogameplatform.api.delivery.catalogue.ExternalPlayerScoreMapper;
import com.videogameplatform.api.generated.model.CommunityRankingScore;
import com.videogameplatform.api.generated.model.CompactReleaseSummary;
import com.videogameplatform.api.generated.model.GameRankingItem;
import com.videogameplatform.api.generated.model.GameRankingPage;
import com.videogameplatform.api.generated.model.Genre;
import com.videogameplatform.api.generated.model.PageMetadata;
import com.videogameplatform.api.generated.model.Platform;
import com.videogameplatform.api.generated.model.RankingScore;
import com.videogameplatform.api.generated.model.RankingSource;
import com.videogameplatform.catalogue.application.rankings.GameRanking;
import com.videogameplatform.catalogue.application.rankings.RankGamesUseCase;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Maps provider-independent UC-018 results to the generated HTTP contract. */
@Component
final class GameRankingApiMapper {

    private static final int DISPLAY_SCALE = 10;

    private final CatalogueCoverMapper coverMapper;

    GameRankingApiMapper(CatalogueCoverMapper coverMapper) {
        this.coverMapper = coverMapper;
    }

    static RankGamesUseCase.Source toSource(RankingSource source) {
        return switch (source) {
            case igdb_players -> RankGamesUseCase.Source.IGDB_PLAYERS;
            case gameometro_community -> RankGamesUseCase.Source.GAMEOMETRO_COMMUNITY;
        };
    }

    GameRankingPage toResponse(GameRanking ranking, RankingSource source) {
        return new GameRankingPage(
                source,
                ranking.items().stream().map(this::toItem).toList(),
                new PageMetadata(
                        ranking.page().number(),
                        ranking.page().size(),
                        ranking.page().totalItems(),
                        ranking.page().totalPages()));
    }

    private GameRankingItem toItem(GameRanking.Item item) {
        return new GameRankingItem(
                item.position(),
                item.gameId(),
                item.slug(),
                item.canonicalTitle(),
                coverMapper.toResponse(item.primaryCover()),
                item.genres().stream()
                        .map(term -> new Genre(term.code(), term.name()))
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                toReleaseSummary(item.releaseSummary()),
                toScore(item.score()));
    }

    private static RankingScore toScore(GameRanking.Score score) {
        return switch (score) {
            case GameRanking.Score.IgdbPlayers igdb ->
                    ExternalPlayerScoreMapper.toResponse(igdb.score());
            case GameRanking.Score.Community community ->
                    new CommunityRankingScore(
                            "gameometro",
                            "community",
                            community.mean(),
                            DISPLAY_SCALE,
                            community.ratingCount());
        };
    }

    private static CompactReleaseSummary toReleaseSummary(GameRanking.ReleaseSummary source) {
        CompactReleaseSummary summary =
                new CompactReleaseSummary(
                        source.platforms().stream()
                                .map(platform -> new Platform(platform.id(), platform.name()))
                                .collect(Collectors.toCollection(LinkedHashSet::new)),
                        source.totalPlatforms());
        summary.setEarliestKnownYear(source.earliestKnownYear());
        summary.setLatestKnownYear(source.latestKnownYear());
        return summary;
    }
}
