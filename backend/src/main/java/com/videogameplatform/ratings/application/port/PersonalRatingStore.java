package com.videogameplatform.ratings.application.port;

import com.videogameplatform.ratings.application.PersonalRating;
import com.videogameplatform.ratings.application.RatingCommandResult;
import com.videogameplatform.ratings.application.RatingStatistics;
import com.videogameplatform.ratings.domain.RatingScore;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Transactional persistence boundary for current-user rating commands. A write stores the score's
 * whole platform set with the rating; {@code labels} carries the current catalogue label of every
 * platform the game still releases on, and any other platform of the score must already be saved.
 */
public interface PersonalRatingStore {
    Optional<PersonalRating> find(String userId, String gameId);

    RatingCommandResult create(
            String userId,
            String gameId,
            RatingScore score,
            Map<String, String> labels,
            Instant now,
            String versionToken);

    RatingCommandResult update(
            String userId,
            String gameId,
            RatingScore score,
            Map<String, String> labels,
            Instant now,
            String expectedVersion,
            String nextVersion);

    RatingStatistics.Available delete(String userId, String gameId, String expectedVersion);
}
