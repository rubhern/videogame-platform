package com.videogameplatform.ratings.application.port;

import com.videogameplatform.ratings.application.PersonalRating;
import com.videogameplatform.ratings.application.RatingCommandResult;
import com.videogameplatform.ratings.application.RatingStatistics;
import com.videogameplatform.ratings.domain.RatingValue;
import java.time.Instant;
import java.util.Optional;

/** Transactional persistence boundary for current-user rating commands. */
public interface PersonalRatingStore {
    Optional<PersonalRating> find(String userId, String gameId);

    RatingCommandResult create(
            String userId, String gameId, RatingValue value, Instant now, String versionToken);

    RatingCommandResult update(
            String userId,
            String gameId,
            RatingValue value,
            Instant now,
            String expectedVersion,
            String nextVersion);

    RatingStatistics.Available delete(String userId, String gameId, String expectedVersion);
}
