package com.videogameplatform.catalogue.application.releases;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Set;

/**
 * Keeps Catalogue's projection of each game's Gameómetro community score, the input of the
 * release explorer's rating order (UC-001, ADR-0029) and of TOPómetro's community ranking (UC-018,
 * ADR-0034). Ratings owns every rating and its summary; it calls this contract inside the
 * transaction that changes them, so the projection commits or rolls back with the rating.
 * Catalogue never reads Ratings tables.
 */
public interface RecordCommunityScoresUseCase {

    /** The most game ids one {@link #unprojected} call accepts. */
    int MAX_BATCH = 500;

    /** The places at which Ratings compares unrounded means (AGG-004). */
    int EXACT_MEAN_SCALE = 18;

    /**
     * Replaces one game's projected score. A score without ratings removes it, so the game has no
     * score rather than a zero one.
     */
    void replace(String gameId, CommunityScore score);

    /**
     * The subset of {@code gameIds} that holds no complete projected score, either none at all or
     * one projected before the unrounded mean existed, for a bounded backfill batch.
     */
    Set<String> unprojected(Collection<String> gameIds);

    /**
     * The unrounded community mean of one contribution per rating, at most at {@link
     * #EXACT_MEAN_SCALE} places, and the number of ratings behind it. The mean is absent exactly
     * when there are no ratings.
     */
    record CommunityScore(BigDecimal exactMean, int ratingCount) {
        public CommunityScore {
            if (ratingCount < 0) {
                throw new IllegalArgumentException("Rating count must not be negative");
            }
            if ((exactMean == null) != (ratingCount == 0)) {
                throw new IllegalArgumentException("A mean exists exactly when ratings exist");
            }
            if (exactMean != null
                    && (exactMean.scale() > EXACT_MEAN_SCALE
                            || exactMean.compareTo(BigDecimal.ONE) < 0
                            || exactMean.compareTo(BigDecimal.TEN) > 0)) {
                throw new IllegalArgumentException("Mean must lie from 1 to 10 at 18 places");
            }
        }

        /** The mean at the one decimal Gameómetro shows, half up (AGG-005); null without ratings. */
        public BigDecimal mean() {
            return exactMean == null ? null : exactMean.setScale(1, RoundingMode.HALF_UP);
        }
    }
}
