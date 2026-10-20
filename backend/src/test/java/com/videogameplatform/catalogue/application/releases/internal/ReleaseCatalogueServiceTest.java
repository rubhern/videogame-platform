package com.videogameplatform.catalogue.application.releases.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.CatalogueReleaseStatus;
import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.cover.port.ProviderCoverReferenceResolver;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.releases.ReleaseQueryValidationException;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.SourceKind;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReleaseCatalogueServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T10:00:00Z");
    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
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

        var page = service(port, countingClock).browse(query(3, 20));

        // 13 August 2026 is a Thursday: the default range is its Monday-to-Sunday week.
        assertThat(captured.get().window().from()).isEqualTo("2026-08-10");
        assertThat(captured.get().window().to()).isEqualTo("2026-08-16");
        assertThat(captured.get().evaluatedOn()).isEqualTo("2026-08-13");
        assertThat(captured.get().sort()).isEqualTo(BrowseReleasesUseCase.Sort.RELEASE_DATE);
        assertThat(captured.get().pagination().offset()).isEqualTo(40);
        assertThat(captured.get().pagination().pageSize()).isEqualTo(20);
        assertThat(captured.get().includeApproximateDates()).isFalse();
        assertThat(page.window())
                .isEqualTo(
                        new BrowseReleasesResult.Window(
                                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 16)));
        assertThat(captured.get().releaseGroupLimit()).isEqualTo(25);
        assertThat(page.items()).hasSize(1);
        assertThat(page.page().totalItems()).isEqualTo(10_000);
        assertThat(page.page().totalPages()).isEqualTo(500);
        assertThat(instantReads).hasValue(1);
    }

    @Test
    void derivesTheCurrentWeekInMadridAcrossMidnightAndYearEnds() {
        AtomicReference<ReleaseBrowseReadPort.Criteria> captured = new AtomicReference<>();
        ReleaseBrowseReadPort port =
                criteria -> {
                    captured.set(criteria);
                    return Optional.of(result(List.of(), 0));
                };

        // Sunday 23:30 UTC is already Monday in Madrid, which starts a new week.
        service(port, Clock.fixed(Instant.parse("2026-08-16T22:30:00Z"), MADRID))
                .browse(query(1, 20));
        assertThat(captured.get().window().from()).isEqualTo("2026-08-17");
        assertThat(captured.get().window().to()).isEqualTo("2026-08-23");

        // A week that starts in one year and ends in the next stays one Monday-to-Sunday range.
        service(port, Clock.fixed(Instant.parse("2026-12-31T10:00:00Z"), MADRID))
                .browse(query(1, 20));
        assertThat(captured.get().window().from()).isEqualTo("2026-12-28");
        assertThat(captured.get().window().to()).isEqualTo("2027-01-03");
    }

    @Test
    void forwardsARequestedRangeWithItsFiltersOrderAndPage() {
        AtomicReference<ReleaseBrowseReadPort.Criteria> captured = new AtomicReference<>();
        var service =
                service(
                        criteria -> {
                            captured.set(criteria);
                            return Optional.of(result(List.of(), 0));
                        });

        var page =
                service.browse(
                        new BrowseReleasesUseCase.Query(
                                LocalDate.of(2026, 7, 1),
                                LocalDate.of(2026, 9, 30),
                                BrowseReleasesUseCase.Sort.RATING,
                                true,
                                List.of("platform-1"),
                                List.of("region-1"),
                                3,
                                20));

        // The opt-in only widens the predicate; range, filters, order and page are unchanged.
        assertThat(captured.get().includeApproximateDates()).isTrue();
        assertThat(captured.get().window().from()).isEqualTo("2026-07-01");
        assertThat(captured.get().window().to()).isEqualTo("2026-09-30");
        assertThat(captured.get().sort()).isEqualTo(BrowseReleasesUseCase.Sort.RATING);
        assertThat(captured.get().platformIds()).containsExactly("platform-1");
        assertThat(captured.get().regionIds()).containsExactly("region-1");
        assertThat(captured.get().pagination().offset()).isEqualTo(40);
        assertThat(page.window().from()).isEqualTo("2026-07-01");
    }

    @Test
    void acceptsSingleDayPastFutureAndMaximumRanges() {
        AtomicReference<ReleaseBrowseReadPort.Criteria> captured = new AtomicReference<>();
        var service =
                service(
                        criteria -> {
                            captured.set(criteria);
                            return Optional.of(result(List.of(), 0));
                        });

        for (LocalDate[] range :
                new LocalDate[][] {
                    {LocalDate.of(2026, 8, 13), LocalDate.of(2026, 8, 13)},
                    {LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29)},
                    {LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 31)},
                    {LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31)}
                }) {
            service.browse(range(range[0], range[1], false));
            assertThat(captured.get().window().from()).isEqualTo(range[0]);
            assertThat(captured.get().window().to()).isEqualTo(range[1]);
        }
    }

    @Test
    void dropsTheApproximateDateChoiceForARangeThatEndsBeforeToday() {
        AtomicReference<ReleaseBrowseReadPort.Criteria> captured = new AtomicReference<>();
        var service =
                service(
                        criteria -> {
                            captured.set(criteria);
                            return Optional.of(result(List.of(), 0));
                        });

        service.browse(range(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 12), true));
        assertThat(captured.get().includeApproximateDates()).isFalse();

        // A range that reaches today still has a part from today on for the opt-in to widen.
        service.browse(range(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 13), true));
        assertThat(captured.get().includeApproximateDates()).isTrue();
    }

    @Test
    void rejectsIncompleteReversedAndOversizedRangesBeforeReading() {
        var service =
                service(
                        criteria -> {
                            throw new AssertionError("An invalid range must never be read");
                        });

        assertRangeRejected(
                service,
                null,
                LocalDate.of(2026, 8, 31),
                ReleaseQueryValidationException.Code.RANGE_START_MISSING);
        assertRangeRejected(
                service,
                LocalDate.of(2026, 8, 1),
                null,
                ReleaseQueryValidationException.Code.RANGE_END_MISSING);
        assertRangeRejected(
                service,
                LocalDate.of(2026, 8, 31),
                LocalDate.of(2026, 8, 1),
                ReleaseQueryValidationException.Code.RANGE_INVALID);
        // 93 days, one more than the bound, even across a year end.
        assertRangeRejected(
                service,
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2027, 2, 1),
                ReleaseQueryValidationException.Code.RANGE_INVALID);
        assertRangeRejected(
                service,
                LocalDate.of(9999, 12, 1),
                LocalDate.of(9999, 12, 31).plusDays(1),
                ReleaseQueryValidationException.Code.RANGE_INVALID);
    }

    @Test
    void mapsTypedQualityAndCoverReferencesWithoutProviderUrlsInApplicationLogic() {
        CatalogueCoverReference.Provider cover =
                new CatalogueCoverReference.Provider(
                        "IGDB", "co-safe_1", "Cover", "https://www.igdb.com/games/example");
        ReleaseBrowseReadPort.Item item = release("release-provider", cover);

        var page = service(criteria -> Optional.of(result(List.of(item), 1))).browse(query(1, 20));

        assertThat(page.items().getFirst().primaryCover())
                .isEqualTo(
                        new CatalogueCover.Provider(
                                URI.create("https://images.example.test/covers/co-safe_1.webp"),
                                "Cover",
                                new CatalogueCover.Attribution(
                                        "Test provider",
                                        URI.create("https://www.igdb.com/games/example"))));
        assertThat(page.items().getFirst().releases().getFirst().status())
                .isEqualTo(CatalogueReleaseStatus.SCHEDULED);
    }

    @Test
    void preservesUnavailableCoverAsProviderIndependentApplicationState() {
        ReleaseBrowseReadPort.Item item =
                release("release-unavailable", new CatalogueCoverReference.Unavailable());

        var page = service(criteria -> Optional.of(result(List.of(item), 1))).browse(query(1, 20));

        assertThat(page.items().getFirst().primaryCover())
                .isEqualTo(new CatalogueCover.Unavailable());
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
                                                        null,
                                                        null,
                                                        BrowseReleasesUseCase.Sort.RELEASE_DATE,
                                                        false,
                                                        List.of("unsupported"),
                                                        List.of(),
                                                        1,
                                                        20)))
                .isInstanceOf(ReleaseQueryValidationException.class)
                .extracting(exception -> ((ReleaseQueryValidationException) exception).code())
                .isEqualTo(ReleaseQueryValidationException.Code.PLATFORM_NOT_SUPPORTED);
    }

    private static ReleaseCatalogueService service(ReleaseBrowseReadPort port) {
        return service(port, Clock.fixed(NOW, MADRID));
    }

    private static ReleaseCatalogueService service(ReleaseBrowseReadPort port, Clock clock) {
        return new ReleaseCatalogueService(
                port,
                new CatalogueCoverPolicy(providerCoverReferenceResolver()),
                clock,
                new ReleaseBrowsePolicy(25));
    }

    private static ProviderCoverReferenceResolver providerCoverReferenceResolver() {
        return (provider, reference, sourceUrl) ->
                new ProviderCoverReferenceResolver.ResolvedProviderCover(
                        URI.create("https://images.example.test/covers/" + reference + ".webp"),
                        "Test provider",
                        URI.create(sourceUrl));
    }

    private static BrowseReleasesUseCase.Query query(int page, int pageSize) {
        return new BrowseReleasesUseCase.Query(
                null,
                null,
                BrowseReleasesUseCase.Sort.RELEASE_DATE,
                false,
                null,
                null,
                page,
                pageSize);
    }

    private static BrowseReleasesUseCase.Query range(
            LocalDate from, LocalDate to, boolean includeApproximateDates) {
        return new BrowseReleasesUseCase.Query(
                from,
                to,
                BrowseReleasesUseCase.Sort.RELEASE_DATE,
                includeApproximateDates,
                null,
                null,
                1,
                20);
    }

    private static void assertRangeRejected(
            ReleaseCatalogueService service,
            LocalDate from,
            LocalDate to,
            ReleaseQueryValidationException.Code code) {
        assertThatThrownBy(() -> service.browse(range(from, to, false)))
                .isInstanceOf(ReleaseQueryValidationException.class)
                .extracting(exception -> ((ReleaseQueryValidationException) exception).code())
                .isEqualTo(code);
    }

    private static ReleaseBrowseReadPort.Result result(
            List<ReleaseBrowseReadPort.Item> items, long totalItems) {
        return new ReleaseBrowseReadPort.Result(
                "v1", List.of(PLATFORM), List.of(REGION), items, totalItems);
    }

    private static ReleaseBrowseReadPort.Item release(String releaseId) {
        return release(
                releaseId,
                new CatalogueCoverReference.Product("/assets/covers/fallback.svg", "Fallback"));
    }

    private static ReleaseBrowseReadPort.Item release(
            String releaseId, CatalogueCoverReference cover) {
        return new ReleaseBrowseReadPort.Item(
                "game-1",
                "game-1",
                "Game One",
                cover,
                List.of(
                        new ReleaseBrowseReadPort.ReleaseRow(
                                releaseId,
                                "game-1",
                                PLATFORM,
                                REGION,
                                new ReleaseDate.YearOnly(Year.of(2027)),
                                ReleaseStatus.SCHEDULED,
                                SourceKind.PRODUCT_CURATED,
                                "Test",
                                "release",
                                null,
                                null,
                                VerificationLevel.PROVIDER_ONLY,
                                ReviewStatus.NOT_REQUIRED,
                                com.videogameplatform.catalogue.domain.ReleaseStage.UNKNOWN)),
                List.of());
    }
}
