package com.videogameplatform.ratings.application.internal;

import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.details.GetGameDetailsUseCase;
import com.videogameplatform.ratings.application.DeletePersonalRatingUseCase;
import com.videogameplatform.ratings.application.GetPersonalRatingUseCase;
import com.videogameplatform.ratings.application.PersonalRating;
import com.videogameplatform.ratings.application.PersonalRatingCommand;
import com.videogameplatform.ratings.application.PutPersonalRatingUseCase;
import com.videogameplatform.ratings.application.RatingAlreadyExistsException;
import com.videogameplatform.ratings.application.RatingCommandResult;
import com.videogameplatform.ratings.application.RatingNotEligibleException;
import com.videogameplatform.ratings.application.RatingNotFoundException;
import com.videogameplatform.ratings.application.RatingPlatformNotSupportedException;
import com.videogameplatform.ratings.application.RatingPlatformsInvalidException;
import com.videogameplatform.ratings.application.RatingScoreSourceInvalidException;
import com.videogameplatform.ratings.application.RatingStatistics;
import com.videogameplatform.ratings.application.RatingValueInvalidException;
import com.videogameplatform.ratings.application.RatingWriteConflictException;
import com.videogameplatform.ratings.application.port.PersonalRatingStore;
import com.videogameplatform.ratings.domain.InvalidRatingScoreException;
import com.videogameplatform.ratings.domain.PlatformScore;
import com.videogameplatform.ratings.domain.RatingEligibilityPolicy;
import com.videogameplatform.ratings.domain.RatingScore;
import com.videogameplatform.ratings.domain.RatingValue;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Framework-independent UC-005, UC-006 and UC-007 orchestration. A command's platforms, when
 * present, replace the saved set; absent, the saved set is kept. A newly named platform must be
 * one of the game's current release platforms, while one already saved stays valid whatever the
 * catalogue now holds.
 */
public final class PersonalRatingService
        implements GetPersonalRatingUseCase, PutPersonalRatingUseCase, DeletePersonalRatingUseCase {
    private final PersonalRatingStore ratings;
    private final GetGameDetailsUseCase games;
    private final Clock clock;
    private final com.videogameplatform.ratings.application.port.GameListingProjection listing;

    public PersonalRatingService(
            PersonalRatingStore ratings,
            GetGameDetailsUseCase games,
            Clock clock,
            com.videogameplatform.ratings.application.port.GameListingProjection listing) {
        this.ratings = ratings;
        this.games = games;
        this.clock = clock;
        this.listing = listing;
    }

    @Override
    public PersonalRating get(String userId, String gameId) {
        return ratings.find(userId, gameId).orElseThrow(RatingNotFoundException::new);
    }

    @Override
    public RatingCommandResult create(String userId, String gameId, PersonalRatingCommand command) {
        List<PlatformScore> requested = validate(command);
        if (ratings.find(userId, gameId).isPresent()) {
            throw new RatingAlreadyExistsException();
        }
        RatingScore score = score(command, requested, List.of());
        GameDetailsResult game = games.get(gameId);
        requireEligibility(game);
        Map<String, String> labels = labels(game, requested, Set.of());
        listing.refresh(gameId);
        return ratings.create(
                userId, gameId, score, labels, clock.instant(), UUID.randomUUID().toString());
    }

    @Override
    public RatingCommandResult update(
            String userId, String gameId, PersonalRatingCommand command, String expectedVersion) {
        List<PlatformScore> requested = validate(command);
        PersonalRating current = get(userId, gameId);
        if (!current.versionToken().equals(expectedVersion)) {
            throw new RatingWriteConflictException();
        }
        RatingScore score = score(command, requested, current.score().platforms());
        GameDetailsResult game = games.get(gameId);
        requireEligibility(game);
        Map<String, String> labels =
                labels(
                        game,
                        score.platforms(),
                        current.platforms().stream()
                                .map(PersonalRating.PlayedPlatform::platformId)
                                .collect(Collectors.toSet()));
        return ratings.update(
                userId,
                gameId,
                score,
                labels,
                clock.instant(),
                expectedVersion,
                UUID.randomUUID().toString());
    }

    @Override
    public RatingStatistics.Available delete(String userId, String gameId, String expectedVersion) {
        return ratings.delete(userId, gameId, expectedVersion);
    }

    private static void requireEligibility(GameDetailsResult game) {
        RatingEligibilityPolicy.Reason reason = RatingContextService.eligibility(game);
        if (reason != RatingEligibilityPolicy.Reason.ELIGIBLE_RELEASE_FOUND) {
            throw new RatingNotEligibleException(reason);
        }
    }

    /**
     * Checks every value and the named platforms before any state is read, so an invalid command
     * reads and changes nothing. Returns the named platforms, or null when the command keeps them.
     */
    private static List<PlatformScore> validate(PersonalRatingCommand command) {
        if (command.value() != null) {
            value(command.value(), null);
        }
        if (command.platforms() == null) {
            return null;
        }
        var platforms = new ArrayList<PlatformScore>(command.platforms().size());
        for (int index = 0; index < command.platforms().size(); index++) {
            var choice = command.platforms().get(index);
            RatingValue value = choice.value() == null ? null : value(choice.value(), index);
            if (choice.platformId() == null || choice.platformId().isBlank()) {
                throw new RatingPlatformNotSupportedException(index);
            }
            platforms.add(new PlatformScore(choice.platformId(), value));
        }
        // The set's own shape is decided now; its score source needs the saved state.
        try {
            return RatingScore.platformSet(platforms);
        } catch (InvalidRatingScoreException exception) {
            throw new RatingPlatformsInvalidException(exception);
        }
    }

    /** The score the command leaves: its platforms, or the saved ones when it names none. */
    private static RatingScore score(
            PersonalRatingCommand command,
            List<PlatformScore> requested,
            List<PlatformScore> saved) {
        try {
            return new RatingScore(
                    command.value() == null ? null : new RatingValue(command.value()),
                    requested == null ? saved : requested);
        } catch (InvalidRatingScoreException exception) {
            if (exception.violation() == InvalidRatingScoreException.Violation.PLATFORMS) {
                throw new RatingPlatformsInvalidException(exception);
            }
            throw new RatingScoreSourceInvalidException(exception);
        }
    }

    /**
     * The current label of every platform of {@code platforms} the game still releases on. Any
     * other platform must already be saved on the rating; a new one is rejected by its position.
     */
    private static Map<String, String> labels(
            GameDetailsResult game, List<PlatformScore> platforms, Set<String> saved) {
        var known = new LinkedHashMap<String, String>();
        game.releases()
                .forEach(
                        release ->
                                known.putIfAbsent(
                                        release.platform().id(), release.platform().name()));
        if (platforms == null) {
            return Map.of();
        }
        var labels = new LinkedHashMap<String, String>();
        for (int index = 0; index < platforms.size(); index++) {
            String platformId = platforms.get(index).platformId();
            String name = known.get(platformId);
            if (name != null) {
                labels.put(platformId, name);
            } else if (!saved.contains(platformId)) {
                throw new RatingPlatformNotSupportedException(index);
            }
        }
        return Map.copyOf(labels);
    }

    private static RatingValue value(int rawValue, Integer platformIndex) {
        try {
            return new RatingValue(rawValue);
        } catch (IllegalArgumentException exception) {
            throw new RatingValueInvalidException(exception, platformIndex);
        }
    }
}
