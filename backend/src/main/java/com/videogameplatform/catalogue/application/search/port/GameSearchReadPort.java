package com.videogameplatform.catalogue.application.search.port;

import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Outbound query port for one bounded page of games matching a normalized query.
 *
 * <p>The port receives an already-normalized query so matching, ranking, counting and paging
 * happen entirely in the store. It never receives the raw visitor text.
 */
public interface GameSearchReadPort {

    Optional<Result> findMatchingGames(Criteria criteria);

    record Criteria(
            String normalizedQuery,
            List<String> tokens,
            Pagination pagination,
            int releaseContextLimit) {

        public Criteria {
            if (normalizedQuery == null || normalizedQuery.isEmpty()) {
                throw new IllegalArgumentException("A normalized catalogue query is required");
            }
            if (tokens == null || tokens.isEmpty()) {
                throw new IllegalArgumentException("At least one query token is required");
            }
            if (releaseContextLimit < 1) {
                throw new IllegalArgumentException("Release context must be bounded to at least 1");
            }
            tokens = List.copyOf(tokens);
        }
    }

    record Pagination(int pageNumber, int pageSize, long offset) {}

    record Result(String publicationVersion, List<Item> items, long totalItems) {}

    record Taxonomy(String id, String name) {}

    record Item(
            String gameId,
            String slug,
            String canonicalTitle,
            String matchedAlias,
            CatalogueCoverReference cover,
            List<ReleaseContext> releaseContext) {}

    record ReleaseContext(
            Taxonomy platform,
            Taxonomy region,
            ReleaseDate releaseDate,
            ReleaseStatus status,
            Instant lastSyncedAt) {}
}
