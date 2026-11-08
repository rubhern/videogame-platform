package com.videogameplatform.catalogue.application.rankings.internal;

import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.internal.CatalogueReadMapping;
import com.videogameplatform.catalogue.application.rankings.GameRanking;
import com.videogameplatform.catalogue.application.rankings.RankGamesUseCase;
import com.videogameplatform.catalogue.application.rankings.port.GameRankingReadPort;
import com.videogameplatform.catalogue.application.releases.ReleaseQueryValidationException;
import com.videogameplatform.catalogue.domain.EffectiveReleaseStatusPolicy;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Framework-independent implementation of UC-018. It derives the evaluation date and the page's
 * offset and normalizes a platform scope; the store ranks. Positions are the offset plus the place
 * on the page, so pages of one sequence never restart or overlap their numbering.
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
                                        normalize(query.platformIds()),
                                        evaluatedOn,
                                        new GameRankingReadPort.Pagination(
                                                query.pageNumber(), query.pageSize(), offset),
                                        SUMMARY_PLATFORM_LIMIT))
                        .orElseThrow(CatalogueNotReadyException::new);
        if (!result.platformsSupported()) {
            throw new ReleaseQueryValidationException(
                    ReleaseQueryValidationException.Code.PLATFORM_NOT_SUPPORTED);
        }

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
                            item.platformRelease()
                                    .map(release -> toPlatformRelease(release, evaluatedOn)),
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

    /** The same deterministic normalization as release discovery: trimmed, distinct, sorted. */
    private static List<String> normalize(List<String> values) {
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    private static GameRanking.PlatformRelease toPlatformRelease(
            GameRankingReadPort.PlatformRelease release, LocalDate evaluatedOn) {
        return new GameRanking.PlatformRelease(
                release.platform(),
                release.region(),
                CatalogueReadMapping.toReleaseDate(release.releaseDate()),
                CatalogueReadMapping.toStatus(
                        EffectiveReleaseStatusPolicy.effectiveStatus(
                                release.status(), release.releaseDate(), evaluatedOn)));
    }
}
