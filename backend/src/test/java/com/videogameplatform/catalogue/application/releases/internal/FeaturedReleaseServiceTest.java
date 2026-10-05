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
import com.videogameplatform.catalogue.application.releases.BrowseFeaturedReleasesUseCase;
import com.videogameplatform.catalogue.application.releases.FeaturedMonthOutOfRangeException;
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
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FeaturedReleaseServiceTest {

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
                        .browse(new BrowseFeaturedReleasesUseCase.Query(Optional.empty()));

        assertThat(result.month()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(result.evaluatedOn()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(captured.get().monthStart()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(captured.get().monthEnd()).isEqualTo(LocalDate.parse("2026-10-31"));
        // One featured release of the month and up to five more.
        assertThat(captured.get().limit()).isEqualTo(6);
        assertThat(captured.get().releaseGroupLimit()).isEqualTo(25);
    }

    @Test
    void anotherMonthKeepsItsOwnBoundsWhileEvaluationStaysToday() {
        var result =
                service(port(new FeaturedReleaseReadPort.Result("v1", false, List.of())))
                        .browse(
                                new BrowseFeaturedReleasesUseCase.Query(
                                        Optional.of(YearMonth.of(2026, 2))));

        assertThat(result.month()).isEqualTo(YearMonth.of(2026, 2));
        assertThat(result.window().from()).isEqualTo(LocalDate.parse("2026-02-01"));
        assertThat(result.window().to()).isEqualTo(LocalDate.parse("2026-02-28"));
        assertThat(result.evaluatedOn()).isEqualTo(LocalDate.parse("2026-10-01"));
    }

    @Test
    void onlyAMonthOfTheCurrentMadridYearIsFeaturedAndAnotherYearIsNeverRead() {
        var service = service(port(new FeaturedReleaseReadPort.Result("v1", false, List.of())));
        for (YearMonth month : List.of(YearMonth.of(2026, 1), YearMonth.of(2026, 12))) {
            assertThat(
                            service.browse(
                                            new BrowseFeaturedReleasesUseCase.Query(
                                                    Optional.of(month)))
                                    .month())
                    .isEqualTo(month);
        }
        captured.set(null);
        for (YearMonth month :
                List.of(YearMonth.of(2025, 12), YearMonth.of(2027, 1), YearMonth.of(2019, 3))) {
            assertThatThrownBy(
                            () ->
                                    service.browse(
                                            new BrowseFeaturedReleasesUseCase.Query(
                                                    Optional.of(month))))
                    .isInstanceOf(FeaturedMonthOutOfRangeException.class);
        }
        assertThat(captured.get()).isNull();
    }

    @Test
    void theCurrentYearFollowsTheMadridDateAcrossNewYear() {
        // 31 December at 23:30 UTC is already 1 January in Madrid, the trusted evaluation zone.
        var service =
                service(
                        port(new FeaturedReleaseReadPort.Result("v1", false, List.of())),
                        Clock.fixed(
                                Instant.parse("2026-12-31T23:30:00Z"), ZoneId.of("Europe/Madrid")));

        assertThat(
                        service.browse(new BrowseFeaturedReleasesUseCase.Query(Optional.empty()))
                                .month())
                .isEqualTo(YearMonth.of(2027, 1));
        assertThatThrownBy(
                        () ->
                                service.browse(
                                        new BrowseFeaturedReleasesUseCase.Query(
                                                Optional.of(YearMonth.of(2026, 12)))))
                .isInstanceOf(FeaturedMonthOutOfRangeException.class);
    }

    @Test
    void namesAnUnrankedMonthAndAnEmptyMonthDifferently() {
        var unranked =
                service(port(new FeaturedReleaseReadPort.Result("v1", true, List.of())))
                        .browse(new BrowseFeaturedReleasesUseCase.Query(Optional.empty()));
        var empty =
                service(port(new FeaturedReleaseReadPort.Result("v1", false, List.of())))
                        .browse(new BrowseFeaturedReleasesUseCase.Query(Optional.empty()));

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
                        .browse(new BrowseFeaturedReleasesUseCase.Query(Optional.empty()));
        var stale =
                service(
                                port(
                                        new FeaturedReleaseReadPort.Result(
                                                "v1",
                                                true,
                                                List.of(item("a", recent), item("b", old)))))
                        .browse(new BrowseFeaturedReleasesUseCase.Query(Optional.empty()));

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
                        .browse(new BrowseFeaturedReleasesUseCase.Query(Optional.empty()));

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
                        .browse(new BrowseFeaturedReleasesUseCase.Query(Optional.empty()));
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
                        .browse(new BrowseFeaturedReleasesUseCase.Query(Optional.empty()));

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
                                        .browse(
                                                new BrowseFeaturedReleasesUseCase.Query(
                                                        Optional.empty())))
                .isInstanceOf(CatalogueNotReadyException.class);
    }

    @Test
    void rejectsAMonthOutsideTheSupportedYears() {
        assertThatThrownBy(
                        () ->
                                new BrowseFeaturedReleasesUseCase.Query(
                                        Optional.of(YearMonth.of(0, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private FeaturedReleaseReadPort port(FeaturedReleaseReadPort.Result result) {
        return criteria -> {
            captured.set(criteria);
            return Optional.of(result);
        };
    }

    private FeaturedReleasesResult.Item only(FeaturedReleaseReadPort.Item item) {
        return service(port(new FeaturedReleaseReadPort.Result("v1", true, List.of(item))))
                .browse(new BrowseFeaturedReleasesUseCase.Query(Optional.empty()))
                .items()
                .getFirst();
    }

    /** Resolvers that name the rendition in the URL, so each test sees which one was chosen. */
    private static FeaturedReleaseService service(FeaturedReleaseReadPort port) {
        return service(port, Clock.fixed(MADRID_FIRST_OF_OCTOBER, ZoneId.of("Europe/Madrid")));
    }

    private static FeaturedReleaseService service(FeaturedReleaseReadPort port, Clock clock) {
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
                new CatalogueFreshnessPolicy(Duration.ofDays(7)));
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
                                observedAt,
                                null,
                                VerificationLevel.PROVIDER_ONLY,
                                ReviewStatus.NOT_REQUIRED,
                                ReleaseStage.UNKNOWN)),
                List.of(),
                Optional.empty());
    }
}
