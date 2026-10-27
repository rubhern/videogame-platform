package com.videogameplatform.catalogue.application.releases.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.CatalogueFreshness;
import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.CatalogueReleaseDate;
import com.videogameplatform.catalogue.application.CatalogueReleaseStatus;
import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.cover.port.ProviderCoverReferenceResolver;
import com.videogameplatform.catalogue.application.cover.port.ProviderImageReferenceResolver;
import com.videogameplatform.catalogue.application.internal.CatalogueFreshnessPolicy;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult.Taxonomy;
import com.videogameplatform.catalogue.application.releases.BrowseUpcomingHighlightsUseCase.Query;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult.FeaturedImage;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult.Selection;
import com.videogameplatform.catalogue.application.releases.ReleaseQueryValidationException;
import com.videogameplatform.catalogue.application.releases.UpcomingHighlightsResult;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort.MediaReference;
import com.videogameplatform.catalogue.application.releases.port.UpcomingHighlightsReadPort;
import com.videogameplatform.catalogue.domain.FeaturedMediaPolicy.ImageKind;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UpcomingHighlightsServiceTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    /** 26 October at UTC is already 27 October in Madrid, the trusted evaluation zone. */
    private static final Instant MADRID_27_OCTOBER = Instant.parse("2026-10-26T23:30:00Z");

    private static final String SOURCE = "https://www.igdb.com/games/title-a";
    private static final Taxonomy PS5 = new Taxonomy("platform-ps5", "PlayStation 5");

    private final AtomicReference<UpcomingHighlightsReadPort.Criteria> captured =
            new AtomicReference<>();

    @Test
    void readsTheMadridHorizonWithTheApprovedLimitAndANormalizedScope() {
        var result =
                service(port(new UpcomingHighlightsReadPort.Result(List.of(PS5), false, List.of())))
                        .browse(new Query(List.of("platform-ps5", "platform-ps5")));

        assertThat(captured.get().evaluatedOn()).isEqualTo(LocalDate.parse("2026-10-27"));
        assertThat(captured.get().horizonEnd()).isEqualTo(LocalDate.parse("2027-04-26"));
        assertThat(captured.get().limit()).isEqualTo(10);
        assertThat(captured.get().platformIds()).containsExactly("platform-ps5");
        assertThat(result.evaluatedOn()).isEqualTo(LocalDate.parse("2026-10-27"));
        assertThat(result.window().from()).isEqualTo(LocalDate.parse("2026-10-27"));
        assertThat(result.window().to()).isEqualTo(LocalDate.parse("2027-04-26"));
        assertThat(result.platforms()).containsExactly(PS5);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "2026-10-27, 2027-04-26",
        "2026-01-01, 2026-06-30",
        "2026-07-01, 2026-12-31",
        "2026-12-31, 2027-06-30",
        // The day six months later does not exist: the horizon ends on that month's last day.
        "2026-08-28, 2027-02-27",
        "2026-08-29, 2027-02-28",
        "2026-08-30, 2027-02-28",
        "2026-08-31, 2027-02-28",
        "2026-09-01, 2027-02-28",
        // In a leap year February reaches its 29th.
        "2027-08-29, 2028-02-28",
        "2027-08-30, 2028-02-29",
        "2027-08-31, 2028-02-29",
        "2028-02-29, 2028-08-28",
        "2026-03-31, 2026-09-30"
    })
    void theHorizonEndsTheDayBeforeTheSameDaySixCalendarMonthsLater(
            LocalDate evaluatedOn, LocalDate horizonEnd) {
        assertThat(UpcomingHighlightsService.horizonEnd(evaluatedOn)).isEqualTo(horizonEnd);
    }

    @Test
    void numbersTheRankedGamesAndGivesTheLeadTheHeroImageAndTheOthersTheCardImage() {
        var artwork = media(ImageKind.ARTWORK, "hero", 2560, 1080);
        var screenshot = media(ImageKind.SCREENSHOT, "card", 1920, 1080);
        var result =
                service(
                                port(
                                        new UpcomingHighlightsReadPort.Result(
                                                List.of(PS5),
                                                true,
                                                List.of(
                                                        item(
                                                                "a",
                                                                Optional.of(artwork),
                                                                Optional.of(screenshot)),
                                                        item(
                                                                "b",
                                                                Optional.of(artwork),
                                                                Optional.of(screenshot)),
                                                        item(
                                                                "c",
                                                                Optional.empty(),
                                                                Optional.empty())))))
                        .browse(new Query(List.of("platform-ps5")));

        assertThat(result.items())
                .extracting(UpcomingHighlightsResult.Item::position)
                .containsExactly(1, 2, 3);
        assertThat(result.items().get(0).image())
                .isInstanceOfSatisfying(
                        FeaturedImage.Provider.class,
                        image -> assertThat(image.url().toString()).endsWith("/hero"));
        assertThat(result.items().get(1).image())
                .isInstanceOfSatisfying(
                        FeaturedImage.Provider.class,
                        image -> assertThat(image.url().toString()).endsWith("/card"));
        // Without landscape media a card falls back to the product treatment, never an invention.
        assertThat(result.items().get(2).image()).isInstanceOf(FeaturedImage.Product.class);
    }

    @Test
    void presentsTheReleaseAtItsOwnPrecisionAsScheduled() {
        var release =
                service(
                                port(
                                        new UpcomingHighlightsReadPort.Result(
                                                List.of(PS5),
                                                true,
                                                List.of(
                                                        item(
                                                                "a",
                                                                Optional.empty(),
                                                                Optional.empty())))))
                        .browse(new Query(List.of("platform-ps5")))
                        .items()
                        .getFirst()
                        .platformRelease();

        assertThat(release.platform()).isEqualTo(PS5);
        assertThat(release.releaseDate())
                .isEqualTo(
                        new CatalogueReleaseDate(
                                CatalogueReleaseDate.Precision.QUARTER, "2027-Q1"));
        assertThat(release.status()).isEqualTo(CatalogueReleaseStatus.SCHEDULED);
    }

    @Test
    void theOldestRankedSignalDecidesTheFreshnessOfTheRanking() {
        var stale = Instant.parse("2026-10-01T05:00:00Z");
        var result =
                service(
                                port(
                                        new UpcomingHighlightsReadPort.Result(
                                                List.of(PS5),
                                                true,
                                                List.of(
                                                        item("a", MADRID_27_OCTOBER),
                                                        item("b", stale)))))
                        .browse(new Query(List.of("platform-ps5")));

        assertThat(result.selection())
                .isEqualTo(new Selection.Ranked(CatalogueFreshness.STALE, stale));
    }

    @Test
    void candidatesWithoutHypesAreUnrankedAndNoCandidatesIsAnEmptyHorizon() {
        var unranked =
                service(port(new UpcomingHighlightsReadPort.Result(List.of(PS5), true, List.of())))
                        .browse(new Query(List.of("platform-ps5")));
        var empty =
                service(port(new UpcomingHighlightsReadPort.Result(List.of(PS5), false, List.of())))
                        .browse(new Query(List.of("platform-ps5")));

        assertThat(unranked.selection()).isInstanceOf(Selection.PopularityUnavailable.class);
        assertThat(unranked.items()).isEmpty();
        assertThat(empty.selection()).isInstanceOf(Selection.NoQualifyingReleases.class);
    }

    @Test
    void anUnknownOrMissingPlatformIsRejectedAsUnsupported() {
        var unknown =
                service(
                        port(
                                new UpcomingHighlightsReadPort.Result(
                                        List.of(PS5), false, List.of())));

        for (List<String> scope :
                List.of(List.of("platform-ps5", "platform-x"), List.<String>of())) {
            assertThatThrownBy(() -> unknown.browse(new Query(scope)))
                    .isInstanceOfSatisfying(
                            ReleaseQueryValidationException.class,
                            exception ->
                                    assertThat(exception.code())
                                            .isEqualTo(
                                                    ReleaseQueryValidationException.Code
                                                            .PLATFORM_NOT_SUPPORTED));
        }
    }

    @Test
    void aCatalogueNeverPublishedIsNotReady() {
        UpcomingHighlightsReadPort none = criteria -> Optional.empty();

        assertThatThrownBy(() -> service(none).browse(new Query(List.of("platform-ps5"))))
                .isInstanceOf(CatalogueNotReadyException.class);
    }

    private UpcomingHighlightsReadPort port(UpcomingHighlightsReadPort.Result result) {
        return criteria -> {
            captured.set(criteria);
            return Optional.of(result);
        };
    }

    /** Resolvers that name the reference in the URL, so each test sees which image was chosen. */
    private static UpcomingHighlightsService service(UpcomingHighlightsReadPort port) {
        return new UpcomingHighlightsService(
                port,
                new CatalogueCoverPolicy(
                        (provider, reference, sourceUrl) ->
                                new ProviderCoverReferenceResolver.ResolvedProviderCover(
                                        URI.create("https://images.example/cover/" + reference),
                                        "IGDB",
                                        URI.create(sourceUrl))),
                (provider, reference, sourceUrl, rendition) ->
                        new ProviderImageReferenceResolver.ResolvedProviderImage(
                                URI.create("https://images.example/" + rendition + "/" + reference),
                                "IGDB",
                                URI.create(sourceUrl)),
                Clock.fixed(MADRID_27_OCTOBER, MADRID),
                new CatalogueFreshnessPolicy(Duration.ofDays(7)));
    }

    private static MediaReference media(ImageKind kind, String reference, int width, int height) {
        return new MediaReference(kind, "IGDB", reference, width, height, false, SOURCE);
    }

    private static UpcomingHighlightsReadPort.Item item(String gameId, Instant observedAt) {
        return item(gameId, Optional.empty(), Optional.empty(), observedAt);
    }

    private static UpcomingHighlightsReadPort.Item item(
            String gameId, Optional<MediaReference> image, Optional<MediaReference> cardImage) {
        return item(gameId, image, cardImage, MADRID_27_OCTOBER);
    }

    private static UpcomingHighlightsReadPort.Item item(
            String gameId,
            Optional<MediaReference> image,
            Optional<MediaReference> cardImage,
            Instant observedAt) {
        return new UpcomingHighlightsReadPort.Item(
                gameId,
                "slug-" + gameId,
                "Title " + gameId,
                new CatalogueCoverReference.Product(
                        "/assets/covers/fallback.svg", "Portada " + gameId),
                observedAt,
                image,
                cardImage,
                List.of(),
                PS5,
                new Taxonomy("region-1", "Mundial"),
                new ReleaseDate.Quarter(2027, 1),
                ReleaseStatus.ANNOUNCED);
    }
}
