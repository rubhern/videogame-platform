package com.videogameplatform.ratings.application.internal;

import com.videogameplatform.catalogue.application.details.GetGameDetailsUseCase;
import com.videogameplatform.ratings.application.DeletePersonalRatingUseCase;
import com.videogameplatform.ratings.application.GetPersonalRatingUseCase;
import com.videogameplatform.ratings.application.PersonalRating;
import com.videogameplatform.ratings.application.PutPersonalRatingUseCase;
import com.videogameplatform.ratings.application.RatingAlreadyExistsException;
import com.videogameplatform.ratings.application.RatingCommandResult;
import com.videogameplatform.ratings.application.RatingNotEligibleException;
import com.videogameplatform.ratings.application.RatingNotFoundException;
import com.videogameplatform.ratings.application.RatingStatistics;
import com.videogameplatform.ratings.application.RatingValueInvalidException;
import com.videogameplatform.ratings.application.RatingWriteConflictException;
import com.videogameplatform.ratings.application.port.PersonalRatingStore;
import com.videogameplatform.ratings.domain.RatingEligibilityPolicy;
import com.videogameplatform.ratings.domain.RatingValue;
import java.time.Clock;
import java.util.UUID;

/** Framework-independent UC-005, UC-006 and UC-007 orchestration. */
public final class PersonalRatingService
        implements GetPersonalRatingUseCase, PutPersonalRatingUseCase, DeletePersonalRatingUseCase {
    private final PersonalRatingStore ratings;
    private final GetGameDetailsUseCase games;
    private final Clock clock;

    public PersonalRatingService(
            PersonalRatingStore ratings, GetGameDetailsUseCase games, Clock clock) {
        this.ratings = ratings;
        this.games = games;
        this.clock = clock;
    }

    @Override
    public PersonalRating get(String userId, String gameId) {
        return ratings.find(userId, gameId).orElseThrow(RatingNotFoundException::new);
    }

    @Override
    public RatingCommandResult create(String userId, String gameId, int rawValue) {
        RatingValue value = value(rawValue);
        if (ratings.find(userId, gameId).isPresent()) {
            throw new RatingAlreadyExistsException();
        }
        requireEligibility(gameId);
        return ratings.create(userId, gameId, value, clock.instant(), UUID.randomUUID().toString());
    }

    @Override
    public RatingCommandResult update(
            String userId, String gameId, int rawValue, String expectedVersion) {
        RatingValue value = value(rawValue);
        PersonalRating current = get(userId, gameId);
        if (!current.versionToken().equals(expectedVersion)) {
            throw new RatingWriteConflictException();
        }
        requireEligibility(gameId);
        return ratings.update(
                userId,
                gameId,
                value,
                clock.instant(),
                expectedVersion,
                UUID.randomUUID().toString());
    }

    @Override
    public RatingStatistics.Available delete(String userId, String gameId, String expectedVersion) {
        return ratings.delete(userId, gameId, expectedVersion);
    }

    private void requireEligibility(String gameId) {
        RatingEligibilityPolicy.Reason reason = RatingContextService.eligibility(games.get(gameId));
        if (reason != RatingEligibilityPolicy.Reason.ELIGIBLE_RELEASE_FOUND) {
            throw new RatingNotEligibleException(reason);
        }
    }

    private static RatingValue value(int rawValue) {
        try {
            return new RatingValue(rawValue);
        } catch (IllegalArgumentException exception) {
            throw new RatingValueInvalidException(exception);
        }
    }
}
