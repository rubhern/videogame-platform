package com.videogameplatform.catalogue.application.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.BrowseReleasesResult;
import com.videogameplatform.catalogue.application.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.ReleaseQueryValidationException;
import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.SourceKind;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReleaseCatalogueServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T10:00:00Z");
    private static final ReleaseBrowseReadPort.Taxonomy PLATFORM =
            new ReleaseBrowseReadPort.Taxonomy("platform-1", "Platform One");
    private static final ReleaseBrowseReadPort.Taxonomy REGION =
            new ReleaseBrowseReadPort.Taxonomy("region-1", "Region One");

    @Test
    void capturesOneLogicalTimeAndRequestsOnlyTheRequiredPage() {
        AtomicReference<ReleaseBrowseReadPort.Criteria> captured = new AtomicReference<>();
        ReleaseBrowseReadPort port =
                criteria -> {
                    captured.set(criteria);
                    return Optional.of(result(List.of(release("release-1")), 10_000));
                };
        AtomicInteger instantReads = new AtomicInteger();
        Clock countingClock =
                new Clock() {
                    @Override
                    public ZoneId getZone() {
                        return ZoneId.of("Europe/Madrid");
                    }

                    @Override
                    public Clock withZone(ZoneId zone) {
                        return this;
                    }

                    @Override
                    public Instant instant() {
                        instantReads.incrementAndGet();
                        return NOW;
                    }
                };

        var page =
                service(port, countingClock)
                        .browse(query(BrowseReleasesUseCase.View.UPCOMING, 3, 20));

        assertThat(captured.get().window().from()).isEqualTo("2026-08-13");
        assertThat(captured.get().window().to()).isEqualTo("2027-02-13");
        assertThat(captured.get().pagination().offset()).isEqualTo(40);
        assertThat(captured.get().pagination().pageSize()).isEqualTo(20);
        assertThat(captured.get().includeUnknownUpcomingDates()).isTrue();
        assertThat(page.items()).hasSize(1);
        assertThat(page.page().totalItems()).isEqualTo(10_000);
        assertThat(page.page().totalPages()).isEqualTo(500);
        assertThat(instantReads).hasValue(1);
    }

    @Test
    void mapsTypedQualityAndCoverReferencesWithoutProviderUrlsInApplicationLogic() {
        ReleaseBrowseReadPort.ProviderCoverReference cover =
                new ReleaseBrowseReadPort.ProviderCoverReference(
                        "IGDB", "co-safe_1", "Cover", "https://www.igdb.com/games/example");
        ReleaseBrowseReadPort.Item item = release("release-provider", cover);

        var page =
                service(criteria -> Optional.of(result(List.of(item), 1)))
                        .browse(query(BrowseReleasesUseCase.View.UPCOMING, 1, 20));

        assertThat(page.items().getFirst().primaryCover())
                .isEqualTo(
                        new BrowseReleasesResult.ProviderCoverReference(
                                "IGDB",
                                "co-safe_1",
                                "Cover",
                                "https://www.igdb.com/games/example"));
        assertThat(page.items().getFirst().release().status())
                .isEqualTo(BrowseReleasesResult.Status.SCHEDULED);
        assertThat(page.items().getFirst().release().freshnessStatus())
                .isEqualTo(BrowseReleasesResult.Freshness.FRESH);
    }

    @Test
    void mapsUnavailableCoverToTheProductFallback() {
        ReleaseBrowseReadPort.Item item =
                release(
                        "release-unavailable",
                        new ReleaseBrowseReadPort.UnavailableCoverReference("Unavailable"));

        var page =
                service(criteria -> Optional.of(result(List.of(item), 1)))
                        .browse(query(BrowseReleasesUseCase.View.UPCOMING, 1, 20));

        assertThat(page.items().getFirst().primaryCover())
                .isEqualTo(
                        new BrowseReleasesResult.FallbackCover(
                                "/assets/covers/fallback.svg", "Carátula oficial no disponible"));
    }

    @Test
    void rejectsIdentifiersOutsideProductTaxonomies() {
        ReleaseBrowseReadPort.Result result =
                new ReleaseBrowseReadPort.Result(
                        "v1", List.of(PLATFORM), List.of(REGION), List.of(), 0);

        assertThatThrownBy(
                        () ->
                                service(criteria -> Optional.of(result))
                                        .browse(
                                                new BrowseReleasesUseCase.Query(
                                                        BrowseReleasesUseCase.View.RECENT,
                                                        "unsupported",
                                                        null,
                                                        1,
                                                        20)))
                .isInstanceOf(ReleaseQueryValidationException.class)
                .extracting(exception -> ((ReleaseQueryValidationException) exception).code())
                .isEqualTo(ReleaseQueryValidationException.Code.PLATFORM_NOT_SUPPORTED);
    }

    private static ReleaseCatalogueService service(ReleaseBrowseReadPort port) {
        return service(port, Clock.fixed(NOW, ZoneId.of("Europe/Madrid")));
    }

    private static ReleaseCatalogueService service(ReleaseBrowseReadPort port, Clock clock) {
        return new ReleaseCatalogueService(
                port,
                clock,
                new ReleaseBrowsePolicy(
                        6, 6, ReleaseBrowsePolicy.UnknownUpcomingDatePolicy.INCLUDE_AS_TBA),
                new CatalogueFreshnessPolicy(Duration.ofDays(7)));
    }

    private static BrowseReleasesUseCase.Query query(
            BrowseReleasesUseCase.View view, int page, int pageSize) {
        return new BrowseReleasesUseCase.Query(view, null, null, page, pageSize);
    }

    private static ReleaseBrowseReadPort.Result result(
            List<ReleaseBrowseReadPort.Item> items, long totalItems) {
        return new ReleaseBrowseReadPort.Result(
                "v1", List.of(PLATFORM), List.of(REGION), items, totalItems);
    }

    private static ReleaseBrowseReadPort.Item release(String releaseId) {
        return release(
                releaseId,
                new ReleaseBrowseReadPort.ProductCoverReference(
                        "/assets/covers/fallback.svg", "Fallback"));
    }

    private static ReleaseBrowseReadPort.Item release(
            String releaseId, ReleaseBrowseReadPort.CoverReference cover) {
        return new ReleaseBrowseReadPort.Item(
                releaseId,
                "game-1",
                "game-1",
                "Game One",
                cover,
                PLATFORM,
                REGION,
                new ReleaseDate.YearOnly(Year.of(2027)),
                ReleaseStatus.SCHEDULED,
                SourceKind.PRODUCT_CURATED,
                "Test",
                "release",
                null,
                NOW,
                null,
                VerificationLevel.PROVIDER_ONLY,
                ReviewStatus.NOT_REQUIRED);
    }
}
