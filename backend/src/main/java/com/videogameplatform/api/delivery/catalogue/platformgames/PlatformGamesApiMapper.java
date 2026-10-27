package com.videogameplatform.api.delivery.catalogue.platformgames;

import com.videogameplatform.api.delivery.catalogue.CatalogueCoverMapper;
import com.videogameplatform.api.generated.model.DayReleaseDate;
import com.videogameplatform.api.generated.model.DiscoveryScores;
import com.videogameplatform.api.generated.model.Genre;
import com.videogameplatform.api.generated.model.MonthReleaseDate;
import com.videogameplatform.api.generated.model.PageMetadata;
import com.videogameplatform.api.generated.model.Platform;
import com.videogameplatform.api.generated.model.PlatformGameItem;
import com.videogameplatform.api.generated.model.PlatformGameReleaseState;
import com.videogameplatform.api.generated.model.PlatformGamesFilters;
import com.videogameplatform.api.generated.model.PlatformGamesPage;
import com.videogameplatform.api.generated.model.PlatformGamesSort;
import com.videogameplatform.api.generated.model.QuarterReleaseDate;
import com.videogameplatform.api.generated.model.Region;
import com.videogameplatform.api.generated.model.ReleaseSummary;
import com.videogameplatform.api.generated.model.UnknownReleaseDate;
import com.videogameplatform.api.generated.model.YearReleaseDate;
import com.videogameplatform.catalogue.application.CatalogueReleaseDate;
import com.videogameplatform.catalogue.application.CatalogueReleaseStatus;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.platformgames.BrowsePlatformGamesUseCase;
import com.videogameplatform.catalogue.application.platformgames.PlatformGames;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Maps provider-independent UC-019 results to the generated HTTP contract. */
@Component
final class PlatformGamesApiMapper {

    private final CatalogueCoverMapper coverMapper;

    PlatformGamesApiMapper(CatalogueCoverMapper coverMapper) {
        this.coverMapper = coverMapper;
    }

    static BrowsePlatformGamesUseCase.Sort toSort(PlatformGamesSort sort) {
        return switch (sort) {
            case release_date_desc -> BrowsePlatformGamesUseCase.Sort.RELEASE_DATE_DESC;
            case release_date_asc -> BrowsePlatformGamesUseCase.Sort.RELEASE_DATE_ASC;
            case title_asc -> BrowsePlatformGamesUseCase.Sort.TITLE_ASC;
            case title_desc -> BrowsePlatformGamesUseCase.Sort.TITLE_DESC;
            case community_score -> BrowsePlatformGamesUseCase.Sort.COMMUNITY_SCORE;
            case external_player_score -> BrowsePlatformGamesUseCase.Sort.EXTERNAL_PLAYER_SCORE;
        };
    }

    static BrowsePlatformGamesUseCase.ReleaseState toReleaseState(PlatformGameReleaseState state) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case released -> BrowsePlatformGamesUseCase.ReleaseState.RELEASED;
            case upcoming -> BrowsePlatformGamesUseCase.ReleaseState.UPCOMING;
        };
    }

    /** Scores are read for the page's games only, after selection, ordering and paging. */
    PlatformGamesPage toResponse(PlatformGames result, Map<String, DiscoveryScores> scores) {
        PlatformGamesFilters filters =
                new PlatformGamesFilters(genres(result.availableFilters().genres()));
        filters.setEarliestYear(result.availableFilters().earliestYear());
        filters.setLatestYear(result.availableFilters().latestYear());
        return new PlatformGamesPage(
                result.platforms().stream()
                        .map(platform -> new Platform(platform.id(), platform.name()))
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                filters,
                result.items().stream().map(item -> toItem(item, scores)).toList(),
                new PageMetadata(
                        result.page().number(),
                        result.page().size(),
                        result.page().totalItems(),
                        result.page().totalPages()));
    }

    private PlatformGameItem toItem(PlatformGames.Item item, Map<String, DiscoveryScores> scores) {
        PlatformGameItem response =
                new PlatformGameItem(
                        item.gameId(),
                        item.slug(),
                        item.canonicalTitle(),
                        coverMapper.toResponse(item.primaryCover()),
                        genres(item.genres()),
                        toRelease(item.platformRelease()),
                        scores.get(item.gameId()));
        response.setMatchedAlias(item.matchedAlias());
        return response;
    }

    private static LinkedHashSet<Genre> genres(List<GameDetailsResult.Term> terms) {
        return terms.stream()
                .map(term -> new Genre(term.code(), term.name()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static ReleaseSummary toRelease(PlatformGames.PlatformRelease release) {
        return new ReleaseSummary(
                new Platform(release.platform().id(), release.platform().name()),
                new Region(release.region().id(), release.region().name()),
                toReleaseDate(release.releaseDate()),
                toStatus(release.status()));
    }

    private static com.videogameplatform.api.generated.model.ReleaseDate toReleaseDate(
            CatalogueReleaseDate source) {
        return switch (source.precision()) {
            case DAY -> new DayReleaseDate("day", LocalDate.parse(source.value()));
            case MONTH -> new MonthReleaseDate("month", source.value());
            case QUARTER -> new QuarterReleaseDate("quarter", source.value());
            case YEAR -> new YearReleaseDate("year", source.value());
            case UNKNOWN -> new UnknownReleaseDate("unknown", null);
        };
    }

    private static ReleaseSummary.StatusEnum toStatus(CatalogueReleaseStatus source) {
        return switch (source) {
            case ANNOUNCED -> ReleaseSummary.StatusEnum.ANNOUNCED;
            case SCHEDULED -> ReleaseSummary.StatusEnum.SCHEDULED;
            case RELEASED -> ReleaseSummary.StatusEnum.RELEASED;
            case DELAYED -> ReleaseSummary.StatusEnum.DELAYED;
            case CANCELLED -> ReleaseSummary.StatusEnum.CANCELLED;
            case UNKNOWN -> ReleaseSummary.StatusEnum.UNKNOWN;
        };
    }
}
