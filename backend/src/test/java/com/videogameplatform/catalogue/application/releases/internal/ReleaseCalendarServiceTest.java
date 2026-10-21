package com.videogameplatform.catalogue.application.releases.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.releases.BrowseReleaseCalendarUseCase.Query;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import com.videogameplatform.catalogue.application.releases.ReleaseCalendarResult;
import com.videogameplatform.catalogue.application.releases.ReleaseQueryValidationException;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.Taxonomy;
import com.videogameplatform.catalogue.application.releases.port.ReleaseCalendarReadPort;
import com.videogameplatform.catalogue.application.releases.port.ReleaseCalendarReadPort.DayCount;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReleaseCalendarServiceTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    private static final Taxonomy PS5 = new Taxonomy("platform-ps5", "PlayStation 5");
    private static final Taxonomy EUROPE = new Taxonomy("region-europe", "Europa");

    @Test
    void readsTheCurrentMonthInMadridWhenNoneIsNamed() {
        // 23:30 UTC on 31 October is already 1 November in Madrid.
        FakePort port = new FakePort();
        ReleaseCalendarResult result =
                service(port, "2026-10-31T23:30:00Z", 180).browse(query(null));

        assertThat(result.month()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(result.evaluatedOn()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(result.window())
                .isEqualTo(
                        new BrowseReleasesResult.Window(
                                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)));
        assertThat(result.navigation())
                .isEqualTo(
                        new ReleaseCalendarResult.Navigation(
                                YearMonth.of(2026, 11),
                                YearMonth.of(2027, 4),
                                Optional.empty(),
                                Optional.of(YearMonth.of(2026, 12))));
        assertThat(port.criteria.evaluatedOn()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void neverReadsAMonthBeforeTheCurrentOne() {
        SharedCalendar calendar = new SharedCalendar();

        assertThatThrownBy(() -> calendar.at("2026-10-21T10:00:00Z").browse(query("2026-09")))
                .isInstanceOfSatisfying(
                        ReleaseQueryValidationException.class,
                        exception ->
                                assertThat(exception.code())
                                        .isEqualTo(
                                                ReleaseQueryValidationException.Code
                                                        .MONTH_OUT_OF_RANGE));
    }

    @Test
    void reachesTheLastMonthWhollyInsideTheForwardHorizonAndNoFurther() {
        // 21 October + 180 days ends on 19 April 2027, so March is the last whole month.
        SharedCalendar calendar = new SharedCalendar();

        ReleaseCalendarResult last = calendar.at("2026-10-21T10:00:00Z").browse(query("2027-03"));

        assertThat(last.navigation().next()).isEmpty();
        assertThat(last.navigation().previous()).contains(YearMonth.of(2027, 2));
        assertThatThrownBy(() -> calendar.at("2026-10-21T10:00:00Z").browse(query("2027-04")))
                .isInstanceOf(ReleaseQueryValidationException.class);
    }

    @Test
    void stepsAcrossTheYearAndCoversALeapFebruary() {
        SharedCalendar calendar = new SharedCalendar();

        ReleaseCalendarResult january =
                calendar.at("2027-12-20T10:00:00Z").browse(query("2028-01"));
        ReleaseCalendarResult february =
                calendar.at("2027-12-20T10:00:00Z").browse(query("2028-02"));

        assertThat(january.navigation().previous()).contains(YearMonth.of(2027, 12));
        assertThat(january.navigation().next()).contains(YearMonth.of(2028, 2));
        assertThat(february.window().to()).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(calendar.port.criteria.to()).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    void withoutAForwardHorizonOffersOnlyTheCurrentMonth() {
        ReleaseCalendarResult result =
                service(new FakePort(), "2026-10-21T10:00:00Z", 0).browse(query(null));

        assertThat(result.navigation().last()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(result.navigation().next()).isEmpty();
    }

    @Test
    void countsTheCurrentMonthFromTodayAndALaterMonthFromItsFirstDay() {
        FakePort port = new FakePort();
        port.result =
                new ReleaseCalendarReadPort.Result(
                        List.of(PS5),
                        List.of(EUROPE),
                        List.of(new DayCount(LocalDate.of(2026, 10, 30), 12, 260)));
        ReleaseCalendarService calendar = service(port, "2026-10-21T10:00:00Z", 180);

        ReleaseCalendarResult current =
                calendar.browse(
                        new Query(
                                Optional.empty(),
                                List.of(" platform-ps5 ", "platform-ps5"),
                                List.of("region-europe")));

        assertThat(port.criteria.from()).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(port.criteria.to()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(port.criteria.platformIds()).containsExactly("platform-ps5");
        // The month keeps its whole window for the calendar's layout.
        assertThat(current.window().from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(current.activeFilters().platformIds()).containsExactly("platform-ps5");
        assertThat(current.days())
                .containsExactly(
                        new ReleaseCalendarResult.Day(LocalDate.of(2026, 10, 30), 12, 260));

        calendar.browse(query("2026-11"));
        assertThat(port.criteria.from()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void rejectsAnUnknownPlatformAndAnUnpublishedCatalogue() {
        FakePort port = new FakePort();
        assertThatThrownBy(
                        () ->
                                service(port, "2026-10-21T10:00:00Z", 180)
                                        .browse(
                                                new Query(
                                                        Optional.empty(),
                                                        List.of("platform-unknown"),
                                                        List.of())))
                .isInstanceOfSatisfying(
                        ReleaseQueryValidationException.class,
                        exception ->
                                assertThat(exception.code())
                                        .isEqualTo(
                                                ReleaseQueryValidationException.Code
                                                        .PLATFORM_NOT_SUPPORTED));

        port.result = null;
        assertThatThrownBy(() -> service(port, "2026-10-21T10:00:00Z", 180).browse(query(null)))
                .isInstanceOf(CatalogueNotReadyException.class);
    }

    private static Query query(String month) {
        return new Query(Optional.ofNullable(month).map(YearMonth::parse), List.of(), List.of());
    }

    private static ReleaseCalendarService service(
            ReleaseCalendarReadPort port, String instant, int forwardAcquisitionDays) {
        return new ReleaseCalendarService(
                port,
                Clock.fixed(Instant.parse(instant), MADRID),
                new FeaturedPeriodPolicy(forwardAcquisitionDays));
    }

    /** One fake port shared across evaluation instants, with the default 180-day horizon. */
    private static final class SharedCalendar {
        private final FakePort port = new FakePort();

        ReleaseCalendarService at(String instant) {
            return service(port, instant, 180);
        }
    }

    private static final class FakePort implements ReleaseCalendarReadPort {
        private Result result = new Result(List.of(PS5), List.of(EUROPE), List.of());
        private Criteria criteria;

        @Override
        public Optional<Result> countPublishedReleases(Criteria criteria) {
            this.criteria = criteria;
            return Optional.ofNullable(result);
        }
    }
}
