package com.videogameplatform.catalogue.application.settings.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.videogameplatform.catalogue.application.administration.ManageProductSettings.Change;
import com.videogameplatform.catalogue.application.administration.ManageProductSettings.Entry;
import com.videogameplatform.catalogue.application.administration.ManageProductSettings.Origin;
import com.videogameplatform.catalogue.application.administration.ManageProductSettings.Overview;
import com.videogameplatform.catalogue.application.administration.ManageProductSettings.Rule;
import com.videogameplatform.catalogue.application.administration.ManageProductSettings.UpdateOutcome;
import com.videogameplatform.catalogue.application.settings.ProductSetting;
import com.videogameplatform.catalogue.application.settings.TestProductSettings;
import com.videogameplatform.catalogue.application.settings.port.ProductSettingStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductSettingsServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneId.of("Europe/Madrid"));
    private static final String OPERATOR = "6f1c0d9e-1111-4c4c-8888-0123456789ab";

    private final FakeStore store = new FakeStore();
    private final ProductSettingsService service = service(true, true);

    @Test
    void withoutStoredValuesEverySettingTakesItsApprovedOrConfiguredDefault() {
        var settings = service.current();

        assertThat(settings.revision()).isZero();
        assertThat(settings.integer(ProductSetting.UPCOMING_HIGHLIGHTS_HORIZON_MONTHS))
                .isEqualTo(6);
        assertThat(settings.integer(ProductSetting.FEATURED_WEEKS_BACK)).isEqualTo(16);
        assertThat(settings.integer(ProductSetting.FEATURED_MONTHS_FORWARD)).isEqualTo(5);
        assertThat(settings.integer(ProductSetting.RANKINGS_IGDB_MINIMUM_RATINGS)).isEqualTo(50);
        assertThat(settings.integer(ProductSetting.RANKINGS_COMMUNITY_MINIMUM_RATINGS))
                .isEqualTo(1);
        assertThat(settings.calendarMinimumHypes()).isEqualTo(10);
        assertThat(settings.interestBands()).containsExactly(10L, 30L, 100L, 150L);
        assertThat(settings.attention().editorial()).isEqualTo(0.5d);
        assertThat(entry(service.overview(), ProductSetting.SYNCHRONIZATION_UPCOMING_FUTURE_DAYS))
                .satisfies(
                        entry -> {
                            assertThat(entry.value()).isEqualByComparingTo("180");
                            assertThat(entry.origin()).isEqualTo(Origin.DEPLOYMENT);
                        });
        assertThat(entry(service.overview(), ProductSetting.FEATURED_WEEKS_BACK).origin())
                .isEqualTo(Origin.DEFAULT);
    }

    @Test
    void changesTheUpcomingHorizonFromSixToEightMonthsAndAuditsTheOperator() {
        var outcome =
                service.update(
                        0,
                        List.of(change(ProductSetting.UPCOMING_HIGHLIGHTS_HORIZON_MONTHS, "8")),
                        OPERATOR);

        assertThat(outcome).isInstanceOf(UpdateOutcome.Updated.class);
        Overview overview = ((UpdateOutcome.Updated) outcome).overview();
        assertThat(overview.revision()).isEqualTo(1);
        Entry horizon = entry(overview, ProductSetting.UPCOMING_HIGHLIGHTS_HORIZON_MONTHS);
        assertThat(horizon.value()).isEqualByComparingTo("8");
        assertThat(horizon.defaultValue()).isEqualByComparingTo("6");
        assertThat(horizon.origin()).isEqualTo(Origin.OPERATOR);
        assertThat(horizon.changedAt()).contains(CLOCK.instant());
        assertThat(service.current().integer(ProductSetting.UPCOMING_HIGHLIGHTS_HORIZON_MONTHS))
                .isEqualTo(8);
        assertThat(store.audit)
                .singleElement()
                .satisfies(
                        audit -> {
                            assertThat(audit.operator()).isEqualTo(OPERATOR);
                            assertThat(audit.write().previous()).isEqualByComparingTo("6");
                            assertThat(audit.write().next()).isEqualByComparingTo("8");
                        });
        assertThat(overview.recentChanges()).hasSize(1);
        // Eight months from 12 November end on 11 July 2027, beyond the 180-day horizon.
        assertThat(overview.coverage().upcomingHorizonEnd()).isEqualTo(LocalDate.of(2027, 7, 11));
        assertThat(overview.coverage().acquisitionEnd()).isEqualTo(LocalDate.of(2027, 5, 11));
        assertThat(overview.coverage().upcomingBeyondAcquisition()).isTrue();
    }

    @Test
    void anUpdatePreparedFromAnOlderRevisionChangesNothing() {
        service.update(0, List.of(change(ProductSetting.FEATURED_WEEKS_BACK, "8")), OPERATOR);

        var stale =
                service.update(
                        0, List.of(change(ProductSetting.FEATURED_WEEKS_BACK, "4")), OPERATOR);

        assertThat(stale).isEqualTo(new UpdateOutcome.Stale(1));
        assertThat(service.current().integer(ProductSetting.FEATURED_WEEKS_BACK)).isEqualTo(8);
        assertThat(store.audit).hasSize(1);
    }

    @Test
    void aRevisionThatMovesDuringTheWriteIsReportedStale() {
        store.raceOnApply = true;

        var outcome =
                service.update(
                        0, List.of(change(ProductSetting.FEATURED_WEEKS_BACK, "8")), OPERATOR);

        assertThat(outcome).isEqualTo(new UpdateOutcome.Stale(1));
        assertThat(store.audit).isEmpty();
    }

    @Test
    void rejectsUnknownOrRepeatedKeysValuesOutsideTheirRangeOrKindAndEmptyUpdates() {
        assertThat(service.update(0, List.of(new Change("secret.password", value("1"))), OPERATOR))
                .isEqualTo(new UpdateOutcome.UnknownSetting(0));
        assertThat(
                        service.update(
                                0,
                                List.of(
                                        change(ProductSetting.FEATURED_WEEKS_BACK, "2"),
                                        change(ProductSetting.FEATURED_WEEKS_BACK, "3")),
                                OPERATOR))
                .isEqualTo(new UpdateOutcome.UnknownSetting(1));
        for (String invalid : List.of("0", "25", "6.5", "-1")) {
            assertThat(
                            service.update(
                                    0,
                                    List.of(
                                            change(
                                                    ProductSetting
                                                            .UPCOMING_HIGHLIGHTS_HORIZON_MONTHS,
                                                    invalid)),
                                    OPERATOR))
                    .as(invalid)
                    .isEqualTo(new UpdateOutcome.ValueInvalid(0));
        }
        assertThat(
                        service.update(
                                0,
                                List.of(
                                        change(
                                                ProductSetting.ATTENTION_EDITORIAL_WEIGHT,
                                                "1.00001")),
                                OPERATOR))
                .isEqualTo(new UpdateOutcome.ValueInvalid(0));
        assertThat(service.update(0, List.of(), OPERATOR))
                .isEqualTo(new UpdateOutcome.ChangesInvalid());
        assertThat(store.audit).isEmpty();
        assertThat(store.revision).isZero();
    }

    @Test
    void rejectsCombinationsThatBreakACrossSettingRuleAsAWhole() {
        assertThat(
                        service.update(
                                0,
                                List.of(
                                        change(ProductSetting.ATTENTION_HYPES_WEIGHT, "0"),
                                        change(ProductSetting.ATTENTION_WANT_TO_PLAY_WEIGHT, "0"),
                                        change(ProductSetting.ATTENTION_VISITS_WEIGHT, "0")),
                                OPERATOR))
                .isEqualTo(new UpdateOutcome.Inconsistent(Rule.ATTENTION_PROVIDER_WEIGHT));
        assertThat(
                        service.update(
                                0,
                                List.of(
                                        change(
                                                ProductSetting.CALENDAR_MODERATE_INTEREST_HYPES,
                                                "100")),
                                OPERATOR))
                .isEqualTo(new UpdateOutcome.Inconsistent(Rule.INTEREST_THRESHOLDS_ASCENDING));
        assertThat(store.audit).isEmpty();
        // The same thresholds raised together keep their order and are accepted.
        assertThat(
                        service.update(
                                0,
                                List.of(
                                        change(
                                                ProductSetting.CALENDAR_VERY_HIGH_INTEREST_HYPES,
                                                "500"),
                                        change(ProductSetting.CALENDAR_HIGH_INTEREST_HYPES, "300"),
                                        change(
                                                ProductSetting.CALENDAR_MODERATE_INTEREST_HYPES,
                                                "150")),
                                OPERATOR))
                .isInstanceOf(UpdateOutcome.Updated.class);
        assertThat(service.current().interestBands()).containsExactly(10L, 150L, 300L, 500L);
    }

    @Test
    void restoringTheDefaultClearsTheOverrideAndAnUnchangedValueRecordsNothing() {
        service.update(0, List.of(change(ProductSetting.FEATURED_MONTHS_BACK, "3")), OPERATOR);

        var unchanged =
                service.update(
                        1, List.of(change(ProductSetting.FEATURED_MONTHS_BACK, "3.0")), OPERATOR);
        assertThat(((UpdateOutcome.Updated) unchanged).overview().revision()).isEqualTo(1);

        var restored =
                service.update(
                        1,
                        List.of(
                                new Change(
                                        ProductSetting.FEATURED_MONTHS_BACK.key(),
                                        Optional.empty())),
                        OPERATOR);
        Entry entry =
                entry(
                        ((UpdateOutcome.Updated) restored).overview(),
                        ProductSetting.FEATURED_MONTHS_BACK);
        assertThat(entry.value()).isEqualByComparingTo("10");
        assertThat(entry.origin()).isEqualTo(Origin.DEFAULT);
        assertThat(store.overrides).doesNotContainKey(ProductSetting.FEATURED_MONTHS_BACK.key());
        assertThat(store.audit).hasSize(2);
        assertThat(store.audit.get(1).write().value()).isEmpty();
        assertThat(store.audit.get(1).write().previous()).isEqualByComparingTo("3");
        assertThat(store.audit.get(1).write().next()).isEqualByComparingTo("10");
    }

    @Test
    void anInvalidStoredValueNeverFailsAReadAndFallsBackToTheDefault() {
        store.overrides.put(
                ProductSetting.UPCOMING_HIGHLIGHTS_HORIZON_MONTHS.key(),
                new ProductSettingStore.StoredValue(new BigDecimal("99"), CLOCK.instant()));
        store.overrides.put(
                "retired.setting",
                new ProductSettingStore.StoredValue(BigDecimal.ONE, CLOCK.instant()));
        // Stored thresholds that no longer ascend fall back to their defaults together.
        store.overrides.put(
                ProductSetting.CALENDAR_HIGH_INTEREST_HYPES.key(),
                new ProductSettingStore.StoredValue(new BigDecimal("20"), CLOCK.instant()));

        var settings = service.current();

        assertThat(settings.integer(ProductSetting.UPCOMING_HIGHLIGHTS_HORIZON_MONTHS))
                .isEqualTo(6);
        assertThat(settings.interestBands()).containsExactly(10L, 30L, 100L, 150L);
        Overview overview = service.overview();
        assertThat(entry(overview, ProductSetting.UPCOMING_HIGHLIGHTS_HORIZON_MONTHS).origin())
                .isEqualTo(Origin.INVALID_OVERRIDE);
        assertThat(entry(overview, ProductSetting.CALENDAR_HIGH_INTEREST_HYPES).origin())
                .isEqualTo(Origin.INVALID_OVERRIDE);

        // Restoring the default clears the ignored override.
        var restored =
                service.update(
                        0,
                        List.of(
                                new Change(
                                        ProductSetting.UPCOMING_HIGHLIGHTS_HORIZON_MONTHS.key(),
                                        Optional.empty())),
                        OPERATOR);
        assertThat(
                        entry(
                                        ((UpdateOutcome.Updated) restored).overview(),
                                        ProductSetting.UPCOMING_HIGHLIGHTS_HORIZON_MONTHS)
                                .origin())
                .isEqualTo(Origin.DEFAULT);
    }

    @Test
    void comparesPresentationWithTheScheduledPoliciesOnly() {
        Overview defaults = service.overview();
        // Five months forward from November 2026 is April 2027, inside the 180-day horizon.
        assertThat(defaults.coverage().featuredLastMonth()).isEqualTo(YearMonth.of(2027, 4));
        assertThat(defaults.coverage().featuredBeyondAcquisition()).isFalse();
        assertThat(defaults.coverage().upcomingBeyondAcquisition()).isFalse();
        assertThat(defaults.coverage().acquisitionForwardDays()).isEqualTo(180);

        var nearTermOnly = service(true, false).overview().coverage();
        assertThat(nearTermOnly.acquisitionForwardDays()).isEqualTo(28);
        assertThat(nearTermOnly.featuredBeyondAcquisition()).isTrue();

        var none =
                new ProductSettingsService(
                                store,
                                CLOCK,
                                TestProductSettings.deploymentDefaults(),
                                new ProductSettingsService.ScheduledAcquisition(
                                        false, false, false))
                        .overview()
                        .coverage();
        assertThat(none.acquisitionForwardDays()).isZero();
        assertThat(none.schedulingEnabled()).isFalse();
    }

    @Test
    void aMissingOrInvalidDeploymentDefaultRefusesToStart() {
        var withoutWindows = new HashMap<>(TestProductSettings.deploymentDefaults());
        withoutWindows.remove(ProductSetting.SYNCHRONIZATION_UPCOMING_FUTURE_DAYS);
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new ProductSettingsService(
                                        store,
                                        CLOCK,
                                        withoutWindows,
                                        new ProductSettingsService.ScheduledAcquisition(
                                                true, true, true)));
        var tooWide = new HashMap<>(TestProductSettings.deploymentDefaults());
        tooWide.put(ProductSetting.SYNCHRONIZATION_UPCOMING_FUTURE_DAYS, BigDecimal.valueOf(731));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new ProductSettingsService(
                                        store,
                                        CLOCK,
                                        tooWide,
                                        new ProductSettingsService.ScheduledAcquisition(
                                                true, true, true)));
    }

    private ProductSettingsService service(boolean nearTerm, boolean upcoming) {
        return new ProductSettingsService(
                store,
                CLOCK,
                TestProductSettings.deploymentDefaults(),
                new ProductSettingsService.ScheduledAcquisition(true, nearTerm, upcoming));
    }

    private static Change change(ProductSetting setting, String value) {
        return new Change(setting.key(), value(value));
    }

    private static Optional<BigDecimal> value(String value) {
        return Optional.of(new BigDecimal(value));
    }

    private static Entry entry(Overview overview, ProductSetting setting) {
        return overview.settings().stream()
                .filter(entry -> entry.setting() == setting)
                .findFirst()
                .orElseThrow();
    }

    private record Audit(ProductSettingStore.Write write, String operator, long revision) {}

    /** An in-memory store with the PostgreSQL adapter's revision guard. */
    private static final class FakeStore implements ProductSettingStore {
        private final Map<String, StoredValue> overrides = new HashMap<>();
        private final List<Audit> audit = new ArrayList<>();
        private long revision;
        private boolean raceOnApply;

        @Override
        public Stored read() {
            return new Stored(revision, overrides);
        }

        @Override
        public List<Change> recentChanges(int limit) {
            return audit.reversed().stream()
                    .limit(limit)
                    .map(
                            entry ->
                                    new Change(
                                            entry.revision(),
                                            entry.write().key(),
                                            entry.write().previous(),
                                            entry.write().next(),
                                            entry.write().value().isEmpty(),
                                            CLOCK.instant()))
                    .toList();
        }

        @Override
        public Optional<Long> apply(
                long expectedRevision, List<Write> writes, String operatorId, Instant changedAt) {
            if (raceOnApply) {
                revision++;
                raceOnApply = false;
            }
            if (revision != expectedRevision) {
                return Optional.empty();
            }
            revision++;
            for (Write write : writes) {
                write.value()
                        .ifPresentOrElse(
                                value ->
                                        overrides.put(
                                                write.key(), new StoredValue(value, changedAt)),
                                () -> overrides.remove(write.key()));
                audit.add(new Audit(write, operatorId, revision));
            }
            return Optional.of(revision);
        }
    }
}
