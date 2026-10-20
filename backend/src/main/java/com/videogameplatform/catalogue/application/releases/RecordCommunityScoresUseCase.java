package com.videogameplatform.catalogue.application.releases;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Set;

/**
 * Keeps Catalogue's projection of each game's Gameómetro community score, the input of the
 * release explorer's rating order (UC-001, ADR-0029). Ratings owns every rating and its summary;
 * it calls this contract inside the transaction that changes them, so the projection commits or
 * rolls back with the rating. Catalogue never reads Ratings tables.
 */
public interface RecordCommunityScoresUseCase {

    /** The most game ids one {@link #unprojected} call accepts. */
    int MAX_BATCH = 500;

    /**
     * Replaces one game's projected score. A score without ratings removes it, so the game has no
     * score rather than a zero one.
     */
    void replace(String gameId, CommunityScore score);

    /** The subset of {@code gameIds} that holds no projected score, for a bounded backfill batch. */
    Set<String> unprojected(Collection<String> gameIds);

    /**
     * The community mean at the one decimal Gameómetro shows, and the number of ratings behind it.
     * The mean is absent exactly when there are no ratings.
     */
    record CommunityScore(BigDecimal mean, int ratingCount) {
        public CommunityScore {
            if (ratingCount < 0) {
                throw new IllegalArgumentException("Rating count must not be negative");
            }
            if ((mean == null) != (ratingCount == 0)) {
                throw new IllegalArgumentException("A mean exists exactly when ratings exist");
            }
            if (mean != null
                    && (mean.scale() > 1
                            || mean.compareTo(BigDecimal.ONE) < 0
                            || mean.compareTo(BigDecimal.TEN) > 0)) {
                throw new IllegalArgumentException("Mean must lie from 1 to 10 at one decimal");
            }
        }
    }
}
