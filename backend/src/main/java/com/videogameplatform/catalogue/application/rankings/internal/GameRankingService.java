package com.videogameplatform.catalogue.application.rankings.internal;

import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.rankings.GameRanking;
import com.videogameplatform.catalogue.application.rankings.RankGamesUseCase;
import com.videogameplatform.catalogue.application.rankings.port.GameRankingReadPort;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Framework-independent implementation of UC-018. It derives the evaluation date and the page's
 * offset; the store ranks. Positions are the offset plus the place on the page, so pages of one
 * sequence never restart or overlap their numbering.
 */
public final class GameRankingService implements RankGamesUseCase {

    /** Platforms named by a ranked game's compact release summary, as on search results. */
    static final int SUMMARY_PLATFORM_LIMIT = 3;

    private final GameRankingReadPort readPort;
    private final CatalogueCoverPolicy coverPolicy;
    private final Clock clock;

    public GameRankingService(
            GameRankingReadPort readPort, CatalogueCoverPolicy coverPolicy, Clock clock) {
        this.readPort = readPort;
        this.coverPolicy = coverPolicy;
        this.clock = clock;
    }

    @Override
    public GameRanking rank(Query query) {
        LocalDate evaluatedOn = LocalDate.ofInstant(clock.instant(), clock.getZone());
        long offset = Math.multiplyExact((long) query.pageNumber() - 1, query.pageSize());
        GameRankingReadPort.Result result =
                readPort.findRankedGames(
                                new GameRankingReadPort.Criteria(
                                        query.source(),
                                        evaluatedOn,
                                        new GameRankingReadPort.Pagination(
                                                query.pageNumber(), query.pageSize(), offset),
                                        SUMMARY_PLATFORM_LIMIT))
                        .orElseThrow(CatalogueNotReadyException::new);

        List<GameRanking.Item> items = new ArrayList<>(result.items().size());
        for (GameRankingReadPort.Item item : result.items()) {
            items.add(
                    new GameRanking.Item(
                            offset + items.size() + 1,
                            item.gameId(),
                            item.slug(),
                            item.canonicalTitle(),
                            coverPolicy.resolve(item.cover()),
                            item.genres(),
                            item.releaseSummary(),
                            item.score()));
        }
        long totalPages =
                result.totalItems() / query.pageSize()
                        + (result.totalItems() % query.pageSize() == 0 ? 0 : 1);
        return new GameRanking(
                result.publicationVersion(),
                query.source(),
                items,
                new GameRanking.PageMetadata(
                        query.pageNumber(), query.pageSize(), result.totalItems(), totalPages));
    }
}
