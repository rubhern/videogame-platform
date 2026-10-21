package com.videogameplatform.ratings.application.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.CatalogueReleaseDate;
import com.videogameplatform.catalogue.application.CatalogueReleaseStatus;
import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.details.GetGameDetailsUseCase;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import com.videogameplatform.ratings.application.PersonalRating;
import com.videogameplatform.ratings.application.PersonalRatingCommand;
import com.videogameplatform.ratings.application.PersonalRatingCommand.PlatformChoice;
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
import com.videogameplatform.ratings.domain.RatingEligibilityPolicy;
import com.videogameplatform.ratings.domain.RatingScore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PersonalRatingServiceTest {
    private static final String USER = "10000000-0000-4000-8000-000000000001";
    private static final String GAME = "20000000-0000-4000-8000-000000000002";
    private static final Instant NOW = Instant.parse("2026-08-13T10:00:00Z");

    @Test
    void validatesTheValueBeforeReadingPersonalOrCatalogueState() {
        var store = new FakeStore();
        PersonalRatingService service = service(store, id -> failGameRead());

        assertThatThrownBy(() -> service.create(USER, GAME, PersonalRatingCommand.direct(0)))
                .isInstanceOf(RatingValueInvalidException.class);
        assertThat(store.finds).isZero();
    }

    @Test
    void duplicateCreateChecksTheDeclaredAbsenceBeforeEligibility() {
        var store = new FakeStore();
        store.current = rating(6, "existing-version");
        PersonalRatingService service = service(store, id -> failGameRead());

        assertThatThrownBy(() -> service.create(USER, GAME, PersonalRatingCommand.direct(9)))
                .isInstanceOf(RatingAlreadyExistsException.class);
        assertThat(store.current.value()).isEqualTo(6);
    }

    @Test
    void createAndUpdateReevaluateEligibilityAndUseFreshVersionTokens() {
        var store = new FakeStore();
        PersonalRatingService service = service(store, id -> game(CatalogueReleaseStatus.RELEASED));

        RatingCommandResult created = service.create(USER, GAME, PersonalRatingCommand.direct(7));
        RatingCommandResult updated =
                service.update(
                        USER,
                        GAME,
                        PersonalRatingCommand.direct(9),
                        created.personalRating().versionToken());

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

        assertThatThrownBy(
                        () ->
                                service.update(
                                        USER,
                                        GAME,
                                        PersonalRatingCommand.direct(2),
                                        "stale-version"))
                .isInstanceOf(RatingWriteConflictException.class);
        assertThat(store.current.value()).isEqualTo(8);
        assertThat(store.writes).isZero();
    }

    @Test
    void ineligibleCreateAndUpdatePreservePriorStateWithTheStableReason() {
        var createStore = new FakeStore();
        PersonalRatingService create =
                service(createStore, id -> game(CatalogueReleaseStatus.SCHEDULED));
        assertThatThrownBy(() -> create.create(USER, GAME, PersonalRatingCommand.direct(5)))
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
        assertThatThrownBy(
                        () ->
                                update.update(
                                        USER,
                                        GAME,
                                        PersonalRatingCommand.direct(3),
                                        "current-version"))
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

    @Test
    void scoredPlatformsReplaceTheDirectValueWithTheirMean() {
        var store = new FakeStore();
        PersonalRatingService service = service(store, id -> game(CatalogueReleaseStatus.RELEASED));

        RatingCommandResult created =
                service.create(
                        USER,
                        GAME,
                        command(
                                null,
                                platform("ps5", 9),
                                platform("switch", 8),
                                platform("pc", null)));

        PersonalRating rating = created.personalRating();
        assertThat(rating.mode()).isEqualTo(RatingScore.Mode.PLATFORMS);
        assertThat(rating.value()).isNull();
        assertThat(rating.contribution()).isEqualByComparingTo("8.5");
        assertThat(store.score.scoredPlatforms()).isEqualTo(2);
        assertThat(store.score.platformScoreSum()).isEqualTo(17);
        assertThat(store.labels)
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "ps5", "PlayStation 5",
                                "switch", "Nintendo Switch",
                                "pc", "PC"));
    }

    @Test
    void aDirectCommandKeepsTheSavedPlatformsAndAnEmptySetClearsThem() {
        var store = new FakeStore();
        store.current = rating(7, "v1", played("ps5", null));
        PersonalRatingService service = service(store, id -> game(CatalogueReleaseStatus.RELEASED));

        RatingCommandResult kept =
                service.update(USER, GAME, PersonalRatingCommand.direct(9), "v1");
        assertThat(kept.personalRating().value()).isEqualTo(9);
        assertThat(kept.personalRating().platforms())
                .extracting(PersonalRating.PlayedPlatform::platformId)
                .containsExactly("ps5");

        RatingCommandResult cleared =
                service.update(
                        USER,
                        GAME,
                        new PersonalRatingCommand(9, List.of()),
                        kept.personalRating().versionToken());
        assertThat(cleared.personalRating().platforms()).isEmpty();
        assertThat(cleared.personalRating().value()).isEqualTo(9);
    }

    @Test
    void aDirectValueNeverSilentlyReplacesSavedPlatformScores() {
        var store = new FakeStore();
        store.current = rating(null, "v1", played("ps5", 9), played("switch", 8));
        PersonalRatingService service = service(store, id -> game(CatalogueReleaseStatus.RELEASED));

        assertThatThrownBy(() -> service.update(USER, GAME, PersonalRatingCommand.direct(6), "v1"))
                .isInstanceOf(RatingScoreSourceInvalidException.class);
        assertThat(store.writes).isZero();
        assertThat(store.current.contribution()).isEqualByComparingTo("8.5");
    }

    @Test
    void removingTheLastPlatformScoreNeedsAnExplicitDirectValue() {
        var store = new FakeStore();
        store.current = rating(null, "v1", played("ps5", 9));
        PersonalRatingService service = service(store, id -> game(CatalogueReleaseStatus.RELEASED));

        assertThatThrownBy(
                        () ->
                                service.update(
                                        USER, GAME, command(null, platform("ps5", null)), "v1"))
                .isInstanceOf(RatingScoreSourceInvalidException.class);
        assertThat(store.writes).isZero();

        RatingCommandResult direct =
                service.update(USER, GAME, command(6, platform("ps5", null)), "v1");
        assertThat(direct.personalRating().mode()).isEqualTo(RatingScore.Mode.DIRECT);
        assertThat(direct.personalRating().value()).isEqualTo(6);
        assertThat(direct.personalRating().platforms())
                .containsExactly(new PersonalRating.PlayedPlatform("ps5", "PlayStation 5", null));
    }

    @Test
    void playedPlatformsWithoutScoresNeedADirectValue() {
        var store = new FakeStore();
        PersonalRatingService service = service(store, id -> game(CatalogueReleaseStatus.RELEASED));

        assertThatThrownBy(() -> service.create(USER, GAME, command(null, platform("ps5", null))))
                .isInstanceOf(RatingScoreSourceInvalidException.class);
        assertThatThrownBy(() -> service.create(USER, GAME, command(7, platform("ps5", 8))))
                .isInstanceOf(RatingScoreSourceInvalidException.class);
        assertThat(store.current).isNull();
    }

    @Test
    void validatesThePlatformSetBeforeReadingAnyState() {
        var store = new FakeStore();
        PersonalRatingService service = service(store, id -> failGameRead());

        assertThatThrownBy(
                        () ->
                                service.create(
                                        USER,
                                        GAME,
                                        command(null, platform("ps5", 9), platform("ps5", 7))))
                .isInstanceOf(RatingPlatformsInvalidException.class);
        assertThatThrownBy(
                        () ->
                                service.create(
                                        USER,
                                        GAME,
                                        command(
                                                null,
                                                Stream.iterate(0, n -> n + 1)
                                                        .limit(51)
                                                        .map(n -> platform("p" + n, 5))
                                                        .toArray(PlatformChoice[]::new))))
                .isInstanceOf(RatingPlatformsInvalidException.class);
        assertThatThrownBy(
                        () ->
                                service.create(
                                        USER,
                                        GAME,
                                        command(null, platform("ps5", 9), platform("pc", 11))))
                .isInstanceOfSatisfying(
                        RatingValueInvalidException.class,
                        exception -> assertThat(exception.platformIndex()).isEqualTo(1));
        assertThat(store.finds).isZero();
    }

    @Test
    void aNewPlatformMustBeOneOfTheGameReleasePlatforms() {
        var store = new FakeStore();
        store.current = rating(7, "v1");
        PersonalRatingService service = service(store, id -> game(CatalogueReleaseStatus.RELEASED));

        assertThatThrownBy(
                        () ->
                                service.update(
                                        USER,
                                        GAME,
                                        command(
                                                7,
                                                platform("pc", null),
                                                platform("dreamcast", null)),
                                        "v1"))
                .isInstanceOfSatisfying(
                        RatingPlatformNotSupportedException.class,
                        exception -> assertThat(exception.platformIndex()).isEqualTo(1));
        assertThat(store.writes).isZero();
        assertThat(store.current.platforms()).isEmpty();
    }

    @Test
    void aSavedPlatformStaysValidWhenCatalogueEvidenceNoLongerListsIt() {
        var store = new FakeStore();
        store.current = rating(null, "v1", played("retired", 8));
        PersonalRatingService service = service(store, id -> game(CatalogueReleaseStatus.RELEASED));

        RatingCommandResult updated =
                service.update(
                        USER, GAME, command(null, platform("retired", 6), platform("pc", 9)), "v1");

        assertThat(updated.personalRating().contribution()).isEqualByComparingTo("7.5");
        // Only the platform the game still releases on has a current catalogue label.
        assertThat(store.labels).containsExactly(Map.entry("pc", "PC"));
    }

    private static PersonalRatingService service(
            PersonalRatingStore store, GetGameDetailsUseCase games) {
        return new PersonalRatingService(
                store, games, Clock.fixed(NOW, ZoneOffset.ofHours(2)), gameId -> {});
    }

    private static GameDetailsResult failGameRead() {
        throw new AssertionError("Catalogue must not be read");
    }

    private static GameDetailsResult game(CatalogueReleaseStatus status) {
        return new GameDetailsResult(
                GAME,
                "game",
                "Game",
                List.of(),
                new GameDetailsResult.Summary("editorial", "Summary", "en", null),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new CatalogueCover.Product("/cover.svg", "Cover"),
                List.of(
                        release("pc", "PC", status),
                        release("ps5", "PlayStation 5", status),
                        release("switch", "Nintendo Switch", status),
                        release("ps5", "PlayStation 5", status)),
                LocalDate.of(2026, 8, 13));
    }

    private static BrowseReleasesResult.Release release(
            String platformId, String name, CatalogueReleaseStatus status) {
        return new BrowseReleasesResult.Release(
                "release-" + platformId,
                GAME,
                new BrowseReleasesResult.Taxonomy(platformId, name),
                new BrowseReleasesResult.Taxonomy("eu", "Europe"),
                new CatalogueReleaseDate(CatalogueReleaseDate.Precision.DAY, "2026-08-13"),
                status,
                new BrowseReleasesResult.Provenance(
                        BrowseReleasesResult.Source.OFFICIAL_SOURCE, "Publisher", "release"),
                NOW,
                NOW,
                BrowseReleasesResult.Verification.VERIFIED,
                BrowseReleasesResult.Review.NOT_REQUIRED,
                com.videogameplatform.catalogue.application.CatalogueReleaseStage.UNKNOWN);
    }

    private static PersonalRatingCommand command(Integer value, PlatformChoice... platforms) {
        return new PersonalRatingCommand(value, List.of(platforms));
    }

    private static PlatformChoice platform(String platformId, Integer value) {
        return new PlatformChoice(platformId, value);
    }

    private static PersonalRating.PlayedPlatform played(String platformId, Integer value) {
        return new PersonalRating.PlayedPlatform(platformId, platformId, value);
    }

    private static PersonalRating rating(
            Integer value, String version, PersonalRating.PlayedPlatform... platforms) {
        return new PersonalRating(GAME, value, List.of(platforms), NOW, NOW, version);
    }

    /** Stores what a command leaves; labels name platforms the way the real store would. */
    private static final class FakeStore implements PersonalRatingStore {
        private PersonalRating current;
        private RatingScore score;
        private Map<String, String> labels;
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
                String userId,
                String gameId,
                RatingScore score,
                Map<String, String> labels,
                Instant now,
                String versionToken) {
            return store(score, labels, now, now, versionToken);
        }

        @Override
        public RatingCommandResult update(
                String userId,
                String gameId,
                RatingScore score,
                Map<String, String> labels,
                Instant now,
                String expectedVersion,
                String nextVersion) {
            return store(score, labels, current.createdAt(), now, nextVersion);
        }

        @Override
        public RatingStatistics.Available delete(
                String userId, String gameId, String expectedVersion) {
            current = null;
            writes++;
            return statistics(null);
        }

        private RatingCommandResult store(
                RatingScore score,
                Map<String, String> labels,
                Instant createdAt,
                Instant updatedAt,
                String version) {
            writes++;
            this.score = score;
            this.labels = labels;
            var platforms = new ArrayList<PersonalRating.PlayedPlatform>();
            score.platforms()
                    .forEach(
                            platform ->
                                    platforms.add(
                                            new PersonalRating.PlayedPlatform(
                                                    platform.platformId(),
                                                    labels.getOrDefault(
                                                            platform.platformId(),
                                                            platform.platformId()),
                                                    platform.value() == null
                                                            ? null
                                                            : platform.value().value())));
            current =
                    new PersonalRating(
                            GAME,
                            score.direct() == null ? null : score.direct().value(),
                            platforms,
                            createdAt,
                            updatedAt,
                            version);
            return new RatingCommandResult(current, statistics(score.contribution()));
        }

        private static RatingStatistics.Available statistics(BigDecimal contribution) {
            var buckets = new ArrayList<>(Collections.nCopies(10, 0));
            if (contribution != null) {
                buckets.set(
                        contribution.setScale(0, java.math.RoundingMode.HALF_UP).intValue() - 1, 1);
            }
            return new RatingStatistics.Available(
                    contribution, contribution == null ? 0 : 1, buckets, List.of());
        }
    }
}
