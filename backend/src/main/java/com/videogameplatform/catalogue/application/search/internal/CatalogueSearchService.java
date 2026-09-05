package com.videogameplatform.catalogue.application.search.internal;

import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.internal.CatalogueFreshnessPolicy;
import com.videogameplatform.catalogue.application.internal.CatalogueReadMapping;
import com.videogameplatform.catalogue.application.search.SearchCatalogueResult;
import com.videogameplatform.catalogue.application.search.SearchCatalogueUseCase;
import com.videogameplatform.catalogue.application.search.SearchQueryInvalidException;
import com.videogameplatform.catalogue.application.search.port.GameSearchReadPort;
import com.videogameplatform.catalogue.domain.CatalogueSearchText;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Framework-independent implementation of UC-002.
 *
 * <p>The search only ever reads the current local publication. No provider is contacted, and
 * an unsupported external title is an ordinary empty page rather than a failure.
 */
public final class CatalogueSearchService implements SearchCatalogueUseCase {

    private final GameSearchReadPort readPort;
    private final CatalogueCoverPolicy coverPolicy;
    private final Clock clock;
    private final CatalogueSearchPolicy searchPolicy;
    private final CatalogueFreshnessPolicy freshnessPolicy;

    public CatalogueSearchService(
            GameSearchReadPort readPort,
            CatalogueCoverPolicy coverPolicy,
            Clock clock,
            CatalogueSearchPolicy searchPolicy,
            CatalogueFreshnessPolicy freshnessPolicy) {
        this.readPort = readPort;
        this.coverPolicy = coverPolicy;
        this.clock = clock;
        this.searchPolicy = searchPolicy;
        this.freshnessPolicy = freshnessPolicy;
    }

    @Override
    public SearchCatalogueResult search(Query query) {
        CatalogueSearchText searchText = validated(query.text());
        Instant evaluatedAt = clock.instant();
        long offset = Math.multiplyExact((long) query.pageNumber() - 1, query.pageSize());

        GameSearchReadPort.Result result =
                readPort.findMatchingGames(
                                new GameSearchReadPort.Criteria(
                                        searchText.normalized(),
                                        searchText.tokens(),
                                        new GameSearchReadPort.Pagination(
                                                query.pageNumber(), query.pageSize(), offset),
                                        searchPolicy.releaseContextLimit()))
                        .orElseThrow(CatalogueNotReadyException::new);

        long totalPages =
                result.totalItems() / query.pageSize()
                        + (result.totalItems() % query.pageSize() == 0 ? 0 : 1);

        return new SearchCatalogueResult(
                result.publicationVersion(),
                result.items().stream().map(item -> toItem(item, evaluatedAt)).toList(),
                new SearchCatalogueResult.PageMetadata(
                        query.pageNumber(), query.pageSize(), result.totalItems(), totalPages));
    }

    /**
     * Rejects a query that cannot address the catalogue. A text whose normalized form has no
     * token, such as punctuation only, is as unusable as a blank query and is rejected with the
     * same stable code instead of running an empty match.
     */
    private CatalogueSearchText validated(String text) {
        if (text == null
                || text.codePointCount(0, text.length())
                        > CatalogueSearchPolicy.MAXIMUM_QUERY_CODE_POINTS) {
            throw new SearchQueryInvalidException();
        }
        CatalogueSearchText searchText = CatalogueSearchText.of(text);
        if (searchText.isEmpty()) {
            throw new SearchQueryInvalidException();
        }
        return searchText;
    }

    private SearchCatalogueResult.Item toItem(GameSearchReadPort.Item item, Instant evaluatedAt) {
        List<SearchCatalogueResult.ReleaseContext> releaseContext =
                item.releaseContext().stream()
                        .map(
                                context ->
                                        new SearchCatalogueResult.ReleaseContext(
                                                new SearchCatalogueResult.Taxonomy(
                                                        context.platform().id(),
                                                        context.platform().name()),
                                                new SearchCatalogueResult.Taxonomy(
                                                        context.region().id(),
                                                        context.region().name()),
                                                CatalogueReadMapping.toReleaseDate(
                                                        context.releaseDate()),
                                                CatalogueReadMapping.toStatus(context.status()),
                                                CatalogueReadMapping.toFreshness(
                                                        freshnessPolicy.status(
                                                                context.lastSyncedAt(),
                                                                evaluatedAt))))
                        .toList();
        return new SearchCatalogueResult.Item(
                item.gameId(),
                item.slug(),
                item.canonicalTitle(),
                item.matchedAlias(),
                coverPolicy.resolve(item.cover()),
                releaseContext);
    }
}
