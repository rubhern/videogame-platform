package com.videogameplatform.catalogue.application.platformgames.internal;

import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.internal.CatalogueReadMapping;
import com.videogameplatform.catalogue.application.platformgames.BrowsePlatformGamesUseCase;
import com.videogameplatform.catalogue.application.platformgames.PlatformGames;
import com.videogameplatform.catalogue.application.platformgames.PlatformGamesQueryException;
import com.videogameplatform.catalogue.application.platformgames.port.PlatformGamesReadPort;
import com.videogameplatform.catalogue.application.search.SearchQueryInvalidException;
import com.videogameplatform.catalogue.application.search.internal.CatalogueSearchPolicy;
import com.videogameplatform.catalogue.domain.CatalogueSearchText;
import com.videogameplatform.catalogue.domain.EffectiveReleaseStatusPolicy;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Framework-independent implementation of UC-019. It validates the selection, normalizes the
 * optional search once with the catalogue search rule, derives the evaluation date and the page's
 * offset, and presents each release's effective status; the store selects, presents and pages.
 */
public final class PlatformGamesService implements BrowsePlatformGamesUseCase {

    private final PlatformGamesReadPort readPort;
    private final CatalogueCoverPolicy coverPolicy;
    private final Clock clock;

    public PlatformGamesService(
            PlatformGamesReadPort readPort, CatalogueCoverPolicy coverPolicy, Clock clock) {
        this.readPort = readPort;
        this.coverPolicy = coverPolicy;
        this.clock = clock;
    }

    @Override
    public PlatformGames browse(Query query) {
        if (query.platformIds().isEmpty()) {
            throw new PlatformGamesQueryException(
                    PlatformGamesQueryException.Code.PLATFORM_NOT_SUPPORTED);
        }
        if (query.fromYear() != null
                && query.toYear() != null
                && query.fromYear() > query.toYear()) {
            throw new PlatformGamesQueryException(
                    PlatformGamesQueryException.Code.YEAR_RANGE_INVALID);
        }
        PlatformGamesReadPort.Search search = search(query.text());
        LocalDate evaluatedOn = LocalDate.ofInstant(clock.instant(), clock.getZone());
        long offset = Math.multiplyExact((long) query.pageNumber() - 1, query.pageSize());

        PlatformGamesReadPort.Result result =
                readPort.findPlatformGames(
                                new PlatformGamesReadPort.Criteria(
                                        query.platformIds(),
                                        search,
                                        query.releaseState(),
                                        query.fromYear(),
                                        query.toYear(),
                                        query.genreIds(),
                                        query.sort(),
                                        evaluatedOn,
                                        new PlatformGamesReadPort.Pagination(
                                                query.pageNumber(), query.pageSize(), offset)))
                        .orElseThrow(CatalogueNotReadyException::new);

        if (result.platforms().size() != query.platformIds().size()) {
            throw new PlatformGamesQueryException(
                    PlatformGamesQueryException.Code.PLATFORM_NOT_SUPPORTED);
        }
        if (!result.knownGenreIds().containsAll(query.genreIds())) {
            throw new PlatformGamesQueryException(
                    PlatformGamesQueryException.Code.GENRE_NOT_SUPPORTED);
        }

        List<PlatformGames.Item> items =
                result.items().stream().map(item -> toItem(item, evaluatedOn)).toList();
        long totalPages =
                result.totalItems() / query.pageSize()
                        + (result.totalItems() % query.pageSize() == 0 ? 0 : 1);
        return new PlatformGames(
                result.publicationVersion(),
                result.platforms(),
                new PlatformGames.Filters(
                        result.genres(), result.earliestYear(), result.latestYear()),
                items,
                new PlatformGames.PageMetadata(
                        query.pageNumber(), query.pageSize(), result.totalItems(), totalPages));
    }

    /**
     * No text is no search. A text that cannot address the catalogue, too long or without a
     * token once normalized, is rejected as an explicit search would be, never run as empty.
     */
    private static PlatformGamesReadPort.Search search(String text) {
        if (text == null) {
            return null;
        }
        if (text.codePointCount(0, text.length())
                > CatalogueSearchPolicy.MAXIMUM_QUERY_CODE_POINTS) {
            throw new SearchQueryInvalidException();
        }
        CatalogueSearchText searchText = CatalogueSearchText.of(text);
        if (searchText.isEmpty()) {
            throw new SearchQueryInvalidException();
        }
        return new PlatformGamesReadPort.Search(searchText.normalized(), searchText.tokens());
    }

    private PlatformGames.Item toItem(PlatformGamesReadPort.Item item, LocalDate evaluatedOn) {
        return new PlatformGames.Item(
                item.gameId(),
                item.slug(),
                item.canonicalTitle(),
                item.matchedAlias(),
                coverPolicy.resolve(item.cover()),
                item.genres(),
                new PlatformGames.PlatformRelease(
                        item.platform(),
                        item.region(),
                        CatalogueReadMapping.toReleaseDate(item.releaseDate()),
                        CatalogueReadMapping.toStatus(
                                EffectiveReleaseStatusPolicy.effectiveStatus(
                                        item.status(), item.releaseDate(), evaluatedOn))));
    }
}
