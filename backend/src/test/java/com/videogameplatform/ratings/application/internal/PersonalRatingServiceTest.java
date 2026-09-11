package com.videogameplatform.ratings.application.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.CatalogueFreshness;
import com.videogameplatform.catalogue.application.CatalogueReleaseDate;
import com.videogameplatform.catalogue.application.CatalogueReleaseStatus;
import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.details.GetGameDetailsUseCase;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import com.videogameplatform.ratings.application.PersonalRating;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PersonalRatingServiceTest {
    private static final String USER = "10000000-0000-4000-8000-000000000001";
    private static final String GAME = "20000000-0000-4000-8000-000000000002";
    private static final Instant NOW = Instant.parse("2026-08-13T10:00:00Z");

    @Test
    void validatesTheValueBeforeReadingPersonalOrCatalogueState() {
        var store = new FakeStore();
        PersonalRatingService service = service(store, id -> failGameRead());

        assertThatThrownBy(() -> service.create(USER, GAME, 0))
                .isInstanceOf(RatingValueInvalidException.class);
        assertThat(store.finds).isZero();
    }

    @Test
    void duplicateCreateChecksTheDeclaredAbsenceBeforeEligibility() {
        var store = new FakeStore();
        store.current = rating(6, "existing-version");
        PersonalRatingService service = service(store, id -> failGameRead());

        assertThatThrownBy(() -> service.create(USER, GAME, 9))
                .isInstanceOf(RatingAlreadyExistsException.class);
        assertThat(store.current.value()).isEqualTo(6);
    }

    @Test
    void createAndUpdateReevaluateEligibilityAndUseFreshVersionTokens() {
        var store = new FakeStore();
        PersonalRatingService service = service(store, id -> game(CatalogueReleaseStatus.RELEASED));

        RatingCommandResult created = service.create(USER, GAME, 7);
        RatingCommandResult updated =
                service.update(USER, GAME, 9, created.personalRating().versionToken());

        assertThat(updated.personalRating().value()).isEqualTo(9);
        assertThat(updated.personalRating().createdAt()).isEqualTo(NOW);
        assertThat(updated.personalRating().updatedAt()).isEqualTo(NOW);
        assertThat(updated.personalRating().versionToken())
                .isNotEqualTo(created.personalRating().versionToken());
        assertThat(store.writes).isEqualTo(2);
    }

    @Test
    void staleUpdateStopsBeforeEligibilityAndPreservesTheWinner() {
        var store = new FakeStore();
        store.current = rating(8, "winning-version");
        PersonalRatingService service = service(store, id -> failGameRead());

        assertThatThrownBy(() -> service.update(USER, GAME, 2, "stale-version"))
                .isInstanceOf(RatingWriteConflictException.class);
        assertThat(store.current.value()).isEqualTo(8);
        assertThat(store.writes).isZero();
    }

    @Test
    void ineligibleCreateAndUpdatePreservePriorStateWithTheStableReason() {
        var createStore = new FakeStore();
        PersonalRatingService create =
                service(createStore, id -> game(CatalogueReleaseStatus.SCHEDULED));
        assertThatThrownBy(() -> create.create(USER, GAME, 5))
                .isInstanceOfSatisfying(
                        RatingNotEligibleException.class,
                        exception ->
                                assertThat(exception.reason())
                                        .isEqualTo(
                                                RatingEligibilityPolicy.Reason.RELEASE_NOT_OCCURRED
                                                        .name()));
        assertThat(createStore.current).isNull();

        var updateStore = new FakeStore();
        updateStore.current = rating(7, "current-version");
        PersonalRatingService update =
                service(updateStore, id -> game(CatalogueReleaseStatus.SCHEDULED));
        assertThatThrownBy(() -> update.update(USER, GAME, 3, "current-version"))
                .isInstanceOf(RatingNotEligibleException.class);
        assertThat(updateStore.current.value()).isEqualTo(7);
    }

    @Test
    void deleteNeverEvaluatesCurrentEligibilityAndScopesAbsenceToTheOwner() {
        var store = new FakeStore();
        store.current = rating(10, "current-version");
        PersonalRatingService service = service(store, id -> failGameRead());

        RatingStatistics.Available statistics = service.delete(USER, GAME, "current-version");
        assertThat(statistics.count()).isZero();
        assertThat(store.current).isNull();
        assertThatThrownBy(() -> service.get("another-user", GAME))
                .isInstanceOf(RatingNotFoundException.class);
    }

    private static PersonalRatingService service(
            PersonalRatingStore store, GetGameDetailsUseCase games) {
        return new PersonalRatingService(store, games, Clock.fixed(NOW, ZoneOffset.ofHours(2)));
    }

    private static GameDetailsResult failGameRead() {
        throw new AssertionError("Catalogue must not be read");
    }

    private static GameDetailsResult game(CatalogueReleaseStatus status) {
        var release =
                new BrowseReleasesResult.Release(
                        "release",
                        GAME,
                        new BrowseReleasesResult.Taxonomy("pc", "PC"),
                        new BrowseReleasesResult.Taxonomy("eu", "Europe"),
                        new CatalogueReleaseDate(CatalogueReleaseDate.Precision.DAY, "2026-08-13"),
                        status,
                        new BrowseReleasesResult.Provenance(
                                BrowseReleasesResult.Source.OFFICIAL_SOURCE,
                                "Publisher",
                                "release"),
                        NOW,
                        NOW,
                        NOW,
                        BrowseReleasesResult.Verification.VERIFIED,
                        BrowseReleasesResult.Review.NOT_REQUIRED,
                        CatalogueFreshness.FRESH);
        return new GameDetailsResult(
                GAME,
                "game",
                "Game",
                List.of(),
                new GameDetailsResult.Summary("editorial", "Summary", "en", null),
                new CatalogueCover.Product("/cover.svg", "Cover"),
                List.of(release),
                LocalDate.of(2026, 8, 13));
    }

    private static PersonalRating rating(int value, String version) {
        return new PersonalRating(GAME, value, NOW, NOW, version);
    }

    private static final class FakeStore implements PersonalRatingStore {
        private PersonalRating current;
        private int finds;
        private int writes;

        @Override
        public Optional<PersonalRating> find(String userId, String gameId) {
            finds++;
            return USER.equals(userId) && GAME.equals(gameId)
                    ? Optional.ofNullable(current)
                    : Optional.empty();
        }

        @Override
        public RatingCommandResult create(
                String userId, String gameId, RatingValue value, Instant now, String versionToken) {
            writes++;
            current = new PersonalRating(gameId, value.value(), now, now, versionToken);
            return new RatingCommandResult(current, statistics(value.value()));
        }

        @Override
        public RatingCommandResult update(
                String userId,
                String gameId,
                RatingValue value,
                Instant now,
                String expectedVersion,
                String nextVersion) {
            writes++;
            current =
                    new PersonalRating(
                            gameId, value.value(), current.createdAt(), now, nextVersion);
            return new RatingCommandResult(current, statistics(value.value()));
        }

        @Override
        public RatingStatistics.Available delete(
                String userId, String gameId, String expectedVersion) {
            current = null;
            writes++;
            return statistics(null);
        }

        private static RatingStatistics.Available statistics(Integer value) {
            var buckets = new java.util.ArrayList<>(Collections.nCopies(10, 0));
            if (value != null) {
                buckets.set(value - 1, 1);
            }
            return new RatingStatistics.Available(
                    value == null ? null : java.math.BigDecimal.valueOf(value),
                    value == null ? 0 : 1,
                    buckets);
        }
    }
}
