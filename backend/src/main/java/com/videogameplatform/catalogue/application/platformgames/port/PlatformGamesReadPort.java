package com.videogameplatform.catalogue.application.platformgames.port;

import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.platformgames.BrowsePlatformGamesUseCase.ReleaseState;
import com.videogameplatform.catalogue.application.platformgames.BrowsePlatformGamesUseCase.Sort;
import com.videogameplatform.catalogue.application.platformgames.PlatformGames.Taxonomy;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outbound query port for one bounded page of a platform scope's games. Selection, presentation,
 * filtering, facets, ordering, counting and paging all happen in the store, in one snapshot of the
 * current publication; only the page's games cross it.
 */
public interface PlatformGamesReadPort {

    /** Empty when the catalogue has never been published. */
    Optional<Result> findPlatformGames(Criteria criteria);

    /**
     * @param search the normalized title search, or null for none
     * @param evaluatedOn the trusted {@code Europe/Madrid} date that decides whether a release has
     *     effectively occurred (REL-011, REL-012)
     */
    record Criteria(
            List<String> platformIds,
            Search search,
            ReleaseState releaseState,
            Integer fromYear,
            Integer toYear,
            List<String> genreIds,
            Sort sort,
            LocalDate evaluatedOn,
            Pagination pagination) {
        public Criteria {
            platformIds = List.copyOf(platformIds);
            genreIds = List.copyOf(genreIds);
            if (platformIds.isEmpty()) {
                throw new IllegalArgumentException("A platform scope is required");
            }
            Objects.requireNonNull(sort, "sort");
            Objects.requireNonNull(evaluatedOn, "evaluatedOn");
            Objects.requireNonNull(pagination, "pagination");
        }
    }

    /** An already normalized query; the raw visitor text never reaches the store. */
    record Search(String normalizedQuery, List<String> tokens) {
        public Search {
            if (normalizedQuery == null || normalizedQuery.isEmpty()) {
                throw new IllegalArgumentException("A normalized catalogue query is required");
            }
            tokens = List.copyOf(tokens);
            if (tokens.isEmpty()) {
                throw new IllegalArgumentException("At least one query token is required");
            }
        }
    }

    record Pagination(int pageNumber, int pageSize, long offset) {}

    /**
     * The requested platforms and genres the taxonomy holds, the facets and, only when every
     * requested value exists, the page and the total; otherwise no game was read.
     */
    record Result(
            String publicationVersion,
            List<Taxonomy> platforms,
            List<String> knownGenreIds,
            List<GameDetailsResult.Term> genres,
            Integer earliestYear,
            Integer latestYear,
            List<Item> items,
            long totalItems) {
        public Result {
            platforms = List.copyOf(platforms);
            knownGenreIds = List.copyOf(knownGenreIds);
            genres = List.copyOf(genres);
            items = List.copyOf(items);
        }
    }

    /** One game and its presented release with the persisted status evidence. */
    record Item(
            String gameId,
            String slug,
            String canonicalTitle,
            String matchedAlias,
            CatalogueCoverReference cover,
            List<GameDetailsResult.Term> genres,
            Taxonomy platform,
            Taxonomy region,
            ReleaseDate releaseDate,
            ReleaseStatus status) {
        public Item {
            genres = List.copyOf(genres);
        }
    }
}
