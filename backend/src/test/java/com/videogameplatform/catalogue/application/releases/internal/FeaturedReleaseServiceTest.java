package com.videogameplatform.catalogue.application.releases.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.CatalogueFreshness;
import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.CatalogueReleaseStatus;
import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.cover.port.ProviderCoverReferenceResolver;
import com.videogameplatform.catalogue.application.cover.port.ProviderImageReferenceResolver;
import com.videogameplatform.catalogue.application.internal.CatalogueFreshnessPolicy;
import com.videogameplatform.catalogue.application.releases.BrowseFeaturedReleasesUseCase.Granularity;
import com.videogameplatform.catalogue.application.releases.BrowseFeaturedReleasesUseCase.Query;
import com.videogameplatform.catalogue.application.releases.FeaturedPeriodOutOfRangeException;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult.FeaturedImage;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult.FeaturedImage.Presentation;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult.FeaturedLogo;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult.Selection;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.domain.FeaturedMediaPolicy.ImageKind;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStage;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.SourceKind;
import com.videogameplatform.catalogue.domain.VerificationLevel;
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

class FeaturedReleaseServiceTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    /** 30 September at UTC is already 1 October in Madrid, the trusted evaluation zone. */
    private static final Instant MADRID_FIRST_OF_OCTOBER = Instant.parse("2026-09-30T22:30:00Z");

    private static final String SOURCE = "https://www.igdb.com/games/title-a";
    private static final CatalogueCover.Attribution ATTRIBUTION =
            new CatalogueCover.Attribution("IGDB", URI.create(SOURCE));
    private static final CatalogueCoverReference PRODUCT_COVER =
            new CatalogueCoverReference.Product("/assets/covers/fallback.svg", "Portada a");

    private final AtomicReference<FeaturedReleaseReadPort.Criteria> captured =
            new AtomicReference<>();

    @Test
    void featuresTheCurrentMadridMonthByDefaultWithABoundedRanking() {
        var result =
                service(port(new FeaturedReleaseReadPort.Result("v1", false, List.of())))
                        .browse(Query.current(Granularity.MONTH));

        assertThat(result.granularity()).isEqualTo(Granularity.MONTH);
        assertThat(result.evaluatedOn()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(captured.get().periodStart()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(captured.get().periodEnd()).isEqualTo(LocalDate.parse("2026-10-31"));
        // One featured release of the period and up to five more.
        assertThat(captured.get().limit()).isEqualTo(6);
        assertThat(captured.get().releaseGroupLimit()).isEqualTo(25);
        assertThat(result.navigation().previous()).contains(LocalDate.parse("2026-09-01"));
        assertThat(result.navigation().next()).contains(LocalDate.parse("2026-11-01"));
    }

    @Test
    void passesANormalizedPlatformRestrictionToTheRankingRead() {
        service(port(new FeaturedReleaseReadPort.Result("v1", false, List.of())))
                .browse(
                        new Query(
                                Granularity.WEEK,
                                java.util.Optional.empty(),
                                List.of(" platform-b ", "platform-a", "platform-b", " ")));

        assertThat(captured.get().platformIds()).containsExactly("platform-a", "platform-b");
        service(port(new FeaturedReleaseReadPort.Result("v1", false, List.of())))
                .browse(Query.current(Granularity.WEEK));
        assertThat(captured.get().platformIds()).isEmpty();
    }

    @Test
    void anUnknownRestrictedPlatformIsRejectedAsUnsupported() {
        var service =
                service(port(new FeaturedReleaseReadPort.Result("v1", false, List.of(), false)));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                service.browse(
                                        new Query(
                                                Granularity.MONTH,
                                                java.util.Optional.empty(),
                                                List.of("platform-unknown"))))
                .isInstanceOfSatisfying(
                        com.videogameplatform.catalogue.application.releases
                                .ReleaseQueryValidationException.class,
                        exception ->
                                assertThat(exception.code())
                                        .isEqualTo(
                                                com.videogameplatform.catalogue.application.releases
                                                        .ReleaseQueryValidationException.Code
                                                        .PLATFORM_NOT_SUPPORTED));
    }

    @Test
    void featuresTheCurrentMadridIsoWeekFromMondayToSundayAcrossMonths() {
        // Thursday 1 October 2026 in Madrid belongs to the week of Monday 28 September.
        var result =
                service(port(new FeaturedReleaseReadPort.Result("v1", false, List.of())))
                        .browse(Query.current(Granularity.WEEK));

        assertThat(result.granularity()).isEqualTo(Granularity.WEEK);
        assertThat(result.window().from()).isEqualTo(LocalDate.parse("2026-09-28"));
        assertThat(result.window().to()).isEqualTo(LocalDate.parse("2026-10-04"));
        assertThat(captured.get().periodStart()).isEqualTo(LocalDate.parse("2026-09-28"));
        assertThat(captured.get().periodEnd()).isEqualTo(LocalDate.parse("2026-10-04"));
        assertThat(captured.get().limit()).isEqualTo(6);
        assertThat(result.navigation().previous()).contains(LocalDate.parse("2026-09-21"));
        assertThat(result.navigation().next()).contains(LocalDate.parse("2026-10-05"));
    }

    @Test
    void anotherPeriodKeepsItsOwnBoundsWhileEvaluationStaysToday() {
        var service = service(port(new FeaturedReleaseReadPort.Result("v1", false, List.of())));

        var month = service.browse(month("2026-02-01"));
        assertThat(month.window().from()).isEqualTo(LocalDate.parse("2026-02-01"));
        assertThat(month.window().to()).isEqualTo(LocalDate.parse("2026-02-28"));
        assertThat(month.evaluatedOn()).isEqualTo(LocalDate.parse("2026-10-01"));

        var week = service.browse(week("2026-10-26"));
        assertThat(week.window().from()).isEqualTo(LocalDate.parse("2026-10-26"));
        assertThat(week.window().to()).isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(week.evaluatedOn()).isEqualTo(LocalDate.parse("2026-10-01"));
    }

    @Test
    void weeksReachSixteenWeeksEitherWayAndNoFurther() {
        var service = service(port(new FeaturedReleaseReadPort.Result("v1", false, List.of())));

        var earliest = service.browse(week("2026-06-08"));
        assertThat(earliest.navigation().previous()).isEmpty();
        assertThat(earliest.navigation().next()).contains(LocalDate.parse("2026-06-15"));
        // Sixteen weeks after 28 September reaches into the next year.
        var latest = service.browse(week("2027-01-18"));
        assertThat(latest.window().to()).isEqualTo(LocalDate.parse("2027-01-24"));
        assertThat(latest.navigation().previous()).contains(LocalDate.parse("2027-01-11"));
        assertThat(latest.navigation().next()).isEmpty();

        captured.set(null);
        for (String outside : List.of("2026-06-01", "2027-01-25", "2019-03-04")) {
            assertThatThrownBy(() -> service.browse(week(outside)))
                    .isInstanceOfSatisfying(
                            FeaturedPeriodOutOfRangeException.class,
                            exception ->
                                    assertThat(exception.granularity())
                                            .isEqualTo(Granularity.WEEK));
        }
        assertThat(captured.get()).isNull();
    }

    @Test
    void monthsReachTenMonthsBackAcrossTheYearBoundary() {
        var service = service(port(new FeaturedReleaseReadPort.Result("v1", false, List.of())));

        var earliest = service.browse(month("2025-12-01"));
        assertThat(earliest.window().to()).isEqualTo(LocalDate.parse("2025-12-31"));
        assertThat(earliest.navigation().previous()).isEmpty();
        assertThat(earliest.navigation().next()).contains(LocalDate.parse("2026-01-01"));

        captured.set(null);
        assertThatThrownBy(() -> service.browse(month("2025-11-01")))
                .isInstanceOfSatisfying(
                        FeaturedPeriodOutOfRangeException.class,
                        exception ->
                                assertThat(exception.granularity()).isEqualTo(Granularity.MONTH));
        assertThat(captured.get()).isNull();
    }

    @Test
    void monthsReachForwardOnlyToTheLastMonthWhollyInsideTheAcquisitionHorizon() {
        // 1 October plus 180 days is 30 March 2027: March is not wholly inside, February is.
        var service = service(port(new FeaturedReleaseReadPort.Result("v1", false, List.of())));
        var latest = service.browse(month("2027-02-01"));
        assertThat(latest.navigation().previous()).contains(LocalDate.parse("2027-01-01"));
        assertThat(latest.navigation().next()).isEmpty();
        var march = month("2027-03-01");
        assertThatThrownBy(() -> service.browse(march))
                .isInstanceOf(FeaturedPeriodOutOfRangeException.class);

        // Two days later the horizon ends on 1 April 2027, so March is wholly inside.
        var later =
                service(
                        port(new FeaturedReleaseReadPort.Result("v1", false, List.of())),
                        Clock.fixed(Instant.parse("2026-10-03T10:00:00Z"), MADRID),
                        180);
        assertThat(later.browse(month("2027-03-01")).navigation().next()).isEmpty();

        // Without a horizon only the current month remains forward; weeks keep their own bounds.
        var noHorizon =
                service(
                        port(new FeaturedReleaseReadPort.Result("v1", false, List.of())),
                        Clock.fixed(MADRID_FIRST_OF_OCTOBER, MADRID),
                        0);
        var current = noHorizon.browse(Query.current(Granularity.MONTH));
        assertThat(current.navigation().previous()).contains(LocalDate.parse("2026-09-01"));
        assertThat(current.navigation().next()).isEmpty();
        var november = month("2026-11-01");
        assertThatThrownBy(() -> noHorizon.browse(november))
                .isInstanceOf(FeaturedPeriodOutOfRangeException.class);
        assertThat(noHorizon.browse(week("2027-01-11")).navigation().next())
                .contains(LocalDate.parse("2027-01-18"));
    }

    @Test
    void theCurrentPeriodsFollowTheMadridDateAcrossNewYear() {
        // 31 December at 23:30 UTC is already Friday 1 January 2027 in Madrid.
        var service =
                service(
                        port(new FeaturedReleaseReadPort.Result("v1", false, List.of())),
                        Clock.fixed(Instant.parse("2026-12-31T23:30:00Z"), MADRID),
                        180);

        var month = service.browse(Query.current(Granularity.MONTH));
        assertThat(month.window().from()).isEqualTo(LocalDate.parse("2027-01-01"));
        assertThat(month.navigation().previous()).contains(LocalDate.parse("2026-12-01"));
        // ISO week 53 of 2026 runs from Monday 28 December to Sunday 3 January.
        var week = service.browse(Query.current(Granularity.WEEK));
        assertThat(week.window().from()).isEqualTo(LocalDate.parse("2026-12-28"));
        assertThat(week.window().to()).isEqualTo(LocalDate.parse("2027-01-03"));
        assertThat(week.navigation().next()).contains(LocalDate.parse("2027-01-04"));
        assertThat(service.browse(month("2026-03-01")).navigation().previous()).isEmpty();
    }

    @Test
    void namesAnUnrankedMonthAndAnEmptyMonthDifferently() {
        var unranked =
                service(port(new FeaturedReleaseReadPort.Result("v1", true, List.of())))
                        .browse(Query.current(Granularity.MONTH));
        var empty =
                service(port(new FeaturedReleaseReadPort.Result("v1", false, List.of())))
                        .browse(Query.current(Granularity.MONTH));

        assertThat(unranked.selection()).isInstanceOf(Selection.PopularityUnavailable.class);
        assertThat(unranked.items()).isEmpty();
        assertThat(empty.selection()).isInstanceOf(Selection.NoQualifyingReleases.class);
    }

    @Test
    void theOldestSignalAmongTheRankedGamesDecidesTheSelectionFreshness() {
        Instant recent = MADRID_FIRST_OF_OCTOBER.minus(Duration.ofDays(1));
        Instant old = MADRID_FIRST_OF_OCTOBER.minus(Duration.ofDays(8));
        var fresh =
                service(
                                port(
                                        new FeaturedReleaseReadPort.Result(
                                                "v1", true, List.of(item("a", recent)))))
                        .browse(Query.current(Granularity.MONTH));
        var stale =
                service(
                                port(
                                        new FeaturedReleaseReadPort.Result(
                                                "v1",
                                                true,
                                                List.of(item("a", recent), item("b", old)))))
                        .browse(Query.current(Granularity.MONTH));

        assertThat(fresh.selection())
                .isEqualTo(new Selection.Ranked(CatalogueFreshness.FRESH, recent));
        // A stale ranking is still served: it is the last valid local one.
        assertThat(stale.selection())
                .isEqualTo(new Selection.Ranked(CatalogueFreshness.STALE, old));
        assertThat(stale.items())
                .extracting(item -> item.release().gameId())
                .containsExactly("a", "b");
    }

    @Test
    void mapsRankedItemsWithTheSharedReleaseAndCoverRules() {
        FeaturedReleasesResult result =
                service(
                                port(
                                        new FeaturedReleaseReadPort.Result(
                                                "v1",
                                                true,
                                                List.of(item("a", MADRID_FIRST_OF_OCTOBER)))))
                        .browse(Query.current(Granularity.MONTH));

        var item = result.items().getFirst();
        assertThat(item.release().primaryCover())
                .isEqualTo(new CatalogueCover.Product("/assets/covers/fallback.svg", "Portada a"));
        // The release on the evaluation day has occurred, derived from the trusted date.
        assertThat(item.release().releases().getFirst().status())
                .isEqualTo(CatalogueReleaseStatus.RELEASED);
    }

    @Test
    void fillsAFeaturedFrameWithAStoredLandscapeImageAndOffersItsLogo() {
        var item =
                only(
                        item(
                                "a",
                                PRODUCT_COVER,
                                Optional.of(media(ImageKind.ARTWORK, "keyart", 2560, 1440, false)),
                                Optional.of(media(ImageKind.LOGO, "clear", 900, 320, true))));

        assertThat(item.image())
                .isEqualTo(
                        new FeaturedImage.Provider(
                                FeaturedImage.Kind.ARTWORK,
                                Presentation.FILL,
                                URI.create("https://images.example/LANDSCAPE/keyart"),
                                URI.create("https://images.example/LANDSCAPE_COMPACT/keyart"),
                                "Arte de Title a",
                                ATTRIBUTION));
        // The logo stands in for the rendered title, so the title is its alternative text.
        assertThat(item.logo())
                .contains(
                        new FeaturedLogo(
                                URI.create("https://images.example/LOGO/clear"),
                                "Title a",
                                ATTRIBUTION));
    }

    @Test
    void legacyUnsuitableLandscapeEvidenceUsesTheDesignedFallback() {
        var item =
                only(
                        item(
                                "a",
                                PRODUCT_COVER,
                                Optional.of(media(ImageKind.SCREENSHOT, "tall", 1000, 1500, false)),
                                Optional.empty()));
        assertThat(item.image())
                .isEqualTo(new FeaturedImage.Product("Imagen destacada no disponible de Title a"));
    }

    @Test
    void rankedPositionChoosesThePersistedHeroOrCardSelectionWithoutChangingRanking() {
        var first =
                item(
                        "a",
                        PRODUCT_COVER,
                        Optional.of(media(ImageKind.ARTWORK, "hero", 2560, 1440, false)),
                        Optional.empty());
        var template =
                item(
                        "b",
                        PRODUCT_COVER,
                        Optional.of(media(ImageKind.ARTWORK, "banner", 3840, 1240, false)),
                        Optional.empty());
        var second =
                new FeaturedReleaseReadPort.Item(
                        template.gameId(),
                        template.slug(),
                        template.canonicalTitle(),
                        template.cover(),
                        template.popularityObservedAt(),
                        template.image(),
                        Optional.of(media(ImageKind.SCREENSHOT, "card", 1920, 1080, false)),
                        template.logo(),
                        template.releases(),
                        template.genres(),
                        template.summary());
        var result =
                service(
                                port(
                                        new FeaturedReleaseReadPort.Result(
                                                "v1", true, List.of(first, second))))
                        .browse(Query.current(Granularity.MONTH));
        assertThat(result.items())
                .extracting(item -> item.release().gameId())
                .containsExactly("a", "b");
        assertThat(((FeaturedImage.Provider) result.items().getFirst().image()).url().toString())
                .endsWith("/hero");
        assertThat(((FeaturedImage.Provider) result.items().get(1).image()).url().toString())
                .endsWith("/card");
    }

    @Test
    void withoutAStoredImageTheHeroUsesTheDesignedFallbackAndACardItsWholeCover() {
        var providerCover =
                new CatalogueCoverReference.Provider("IGDB", "cover", "Portada de Title", SOURCE);
        var result =
                service(
                                port(
                                        new FeaturedReleaseReadPort.Result(
                                                "v1",
                                                true,
                                                List.of(
                                                        item(
                                                                "a",
                                                                providerCover,
                                                                Optional.empty(),
                                                                Optional.empty()),
                                                        item(
                                                                "b",
                                                                providerCover,
                                                                Optional.empty(),
                                                                Optional.empty()),
                                                        item("c", MADRID_FIRST_OF_OCTOBER)))))
                        .browse(Query.current(Granularity.MONTH));

        // The hero never presents a cover as its image, even when a provider cover exists.
        assertThat(result.items().getFirst().image())
                .isEqualTo(new FeaturedImage.Product("Imagen destacada no disponible de Title a"));
        assertThat(result.items().get(1).image())
                .isEqualTo(
                        new FeaturedImage.Provider(
                                FeaturedImage.Kind.COVER,
                                Presentation.CONTAIN,
                                URI.create("https://images.example/cover/cover"),
                                URI.create("https://images.example/cover/cover"),
                                "Portada de Title",
                                ATTRIBUTION));
        assertThat(result.items().get(2).image())
                .isEqualTo(new FeaturedImage.Product("Imagen destacada no disponible de Title c"));
    }

    @Test
    void anUnpublishedCatalogueIsNotReady() {
        assertThatThrownBy(
                        () ->
                                service(criteria -> Optional.empty())
                                        .browse(Query.current(Granularity.MONTH)))
                .isInstanceOf(CatalogueNotReadyException.class);
    }

    @Test
    void aPeriodIsNamedByItsFirstDayInsideTheSupportedYears() {
        assertThatThrownBy(() -> month("0000-01-01")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> week("2026-10-13")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> month("2026-10-02")).isInstanceOf(IllegalArgumentException.class);
        assertThat(week("2026-10-12").periodStart()).contains(LocalDate.parse("2026-10-12"));
    }

    private static Query week(String monday) {
        return new Query(Granularity.WEEK, Optional.of(LocalDate.parse(monday)));
    }

    private static Query month(String first) {
        return new Query(Granularity.MONTH, Optional.of(LocalDate.parse(first)));
    }

    private FeaturedReleaseReadPort port(FeaturedReleaseReadPort.Result result) {
        return criteria -> {
            captured.set(criteria);
            return Optional.of(result);
        };
    }

    private FeaturedReleasesResult.Item only(FeaturedReleaseReadPort.Item item) {
        return service(port(new FeaturedReleaseReadPort.Result("v1", true, List.of(item))))
                .browse(Query.current(Granularity.MONTH))
                .items()
                .getFirst();
    }

    /** Resolvers that name the rendition in the URL, so each test sees which one was chosen. */
    private static FeaturedReleaseService service(FeaturedReleaseReadPort port) {
        return service(port, Clock.fixed(MADRID_FIRST_OF_OCTOBER, MADRID), 180);
    }

    private static FeaturedReleaseService service(
            FeaturedReleaseReadPort port, Clock clock, int forwardAcquisitionDays) {
        return new FeaturedReleaseService(
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
                clock,
                new ReleaseBrowsePolicy(25),
                new CatalogueFreshnessPolicy(Duration.ofDays(7)),
                new FeaturedPeriodPolicy(forwardAcquisitionDays));
    }

    private static FeaturedReleaseReadPort.MediaReference media(
            ImageKind kind, String reference, int width, int height, boolean transparent) {
        return new FeaturedReleaseReadPort.MediaReference(
                kind, "IGDB", reference, width, height, transparent, SOURCE);
    }

    private static FeaturedReleaseReadPort.Item item(String gameId, Instant observedAt) {
        return item(
                gameId,
                new CatalogueCoverReference.Product(
                        "/assets/covers/fallback.svg", "Portada " + gameId),
                Optional.empty(),
                Optional.empty(),
                observedAt);
    }

    private static FeaturedReleaseReadPort.Item item(
            String gameId,
            CatalogueCoverReference cover,
            Optional<FeaturedReleaseReadPort.MediaReference> image,
            Optional<FeaturedReleaseReadPort.MediaReference> logo) {
        return item(gameId, cover, image, logo, MADRID_FIRST_OF_OCTOBER);
    }

    private static FeaturedReleaseReadPort.Item item(
            String gameId,
            CatalogueCoverReference cover,
            Optional<FeaturedReleaseReadPort.MediaReference> image,
            Optional<FeaturedReleaseReadPort.MediaReference> logo,
            Instant observedAt) {
        return new FeaturedReleaseReadPort.Item(
                gameId,
                "slug-" + gameId,
                "Title " + gameId,
                cover,
                observedAt,
                image,
                Optional.empty(),
                logo,
                List.of(
                        new ReleaseBrowseReadPort.ReleaseRow(
                                "release-" + gameId,
                                gameId,
                                new ReleaseBrowseReadPort.Taxonomy("platform-1", "Platform One"),
                                new ReleaseBrowseReadPort.Taxonomy("region-1", "Mundial"),
                                new ReleaseDate.Day(LocalDate.parse("2026-10-01")),
                                ReleaseStatus.ANNOUNCED,
                                SourceKind.EXTERNAL_PROVIDER,
                                "IGDB",
                                "release_date",
                                null,
                                null,
                                VerificationLevel.PROVIDER_ONLY,
                                ReviewStatus.NOT_REQUIRED,
                                ReleaseStage.UNKNOWN)),
                List.of(),
                Optional.empty());
    }
}
