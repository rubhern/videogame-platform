package com.videogameplatform.catalogue.application.settings.internal;

import com.videogameplatform.catalogue.application.administration.ManageProductSettings;
import com.videogameplatform.catalogue.application.releases.UpcomingHorizon;
import com.videogameplatform.catalogue.application.settings.CurrentProductSettings;
import com.videogameplatform.catalogue.application.settings.ProductSetting;
import com.videogameplatform.catalogue.application.settings.ProductSettings;
import com.videogameplatform.catalogue.application.settings.port.ProductSettingStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Framework-independent implementation of the product settings (#450, UC-024).
 *
 * <p>The store holds only operator overrides; every other setting takes its default, which is the
 * approved product value or, for the synchronization windows, the deployment's configured policy.
 * A stored value that no longer fits its setting, or a stored combination that breaks a
 * cross-setting rule, is ignored in favour of the defaults of that rule's settings, so a read never
 * fails and a valid operator choice is never overwritten by a restart or a deployment.
 */
// Modulith observability uses a class proxy for this bean, which exposes two use cases.
public class ProductSettingsService implements CurrentProductSettings, ManageProductSettings {

    /** Which scheduled acquisition policies this deployment registers. */
    public record ScheduledAcquisition(
            boolean enabled, boolean nearTermScheduled, boolean upcomingScheduled) {}

    private static final Set<ProductSetting> ATTENTION_PROVIDER_WEIGHTS =
            EnumSet.of(
                    ProductSetting.ATTENTION_HYPES_WEIGHT,
                    ProductSetting.ATTENTION_WANT_TO_PLAY_WEIGHT,
                    ProductSetting.ATTENTION_VISITS_WEIGHT);

    private static final Set<ProductSetting> INTEREST_THRESHOLDS =
            EnumSet.of(
                    ProductSetting.CALENDAR_LOW_INTEREST_HYPES,
                    ProductSetting.CALENDAR_MODERATE_INTEREST_HYPES,
                    ProductSetting.CALENDAR_HIGH_INTEREST_HYPES,
                    ProductSetting.CALENDAR_VERY_HIGH_INTEREST_HYPES);

    private final ProductSettingStore store;
    private final Clock clock;
    private final Map<ProductSetting, BigDecimal> defaults;
    private final ScheduledAcquisition acquisition;

    /**
     * @param deploymentDefaults the configured value of every setting without a fixed default
     * @throws IllegalArgumentException when a default is missing or invalid, so a misconfigured
     *     deployment refuses to start instead of serving an unvalidated value
     */
    public ProductSettingsService(
            ProductSettingStore store,
            Clock clock,
            Map<ProductSetting, BigDecimal> deploymentDefaults,
            ScheduledAcquisition acquisition) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.acquisition = Objects.requireNonNull(acquisition, "acquisition");
        EnumMap<ProductSetting, BigDecimal> resolved = new EnumMap<>(ProductSetting.class);
        for (ProductSetting setting : ProductSetting.values()) {
            BigDecimal value =
                    setting.fixedDefault().orElseGet(() -> deploymentDefaults.get(setting));
            if (!setting.admits(value)) {
                throw new IllegalArgumentException(
                        "The default of product setting " + setting.key() + " is invalid");
            }
            resolved.put(setting, value.stripTrailingZeros());
        }
        new ProductSettings(0, resolved);
        this.defaults = Map.copyOf(resolved);
    }

    @Override
    public ProductSettings current() {
        return resolve(store.read()).settings();
    }

    @Override
    public Overview overview() {
        return overview(resolve(store.read()));
    }

    @Override
    public UpdateOutcome update(long expectedRevision, List<Change> changes, String operatorId) {
        Objects.requireNonNull(operatorId, "operatorId");
        if (changes == null || changes.isEmpty() || changes.size() > MAX_CHANGES) {
            return new UpdateOutcome.ChangesInvalid();
        }
        Resolution before = resolve(store.read());
        if (before.settings().revision() != expectedRevision) {
            return new UpdateOutcome.Stale(before.settings().revision());
        }

        EnumMap<ProductSetting, Optional<BigDecimal>> requested =
                new EnumMap<>(ProductSetting.class);
        for (int index = 0; index < changes.size(); index++) {
            Change change = changes.get(index);
            Optional<ProductSetting> setting =
                    change == null ? Optional.empty() : ProductSetting.byKey(change.key());
            if (setting.isEmpty() || requested.containsKey(setting.get())) {
                return new UpdateOutcome.UnknownSetting(index);
            }
            if (change.value().isPresent() && !setting.get().admits(change.value().get())) {
                return new UpdateOutcome.ValueInvalid(index);
            }
            requested.put(setting.get(), change.value().map(BigDecimal::stripTrailingZeros));
        }

        EnumMap<ProductSetting, BigDecimal> after = new EnumMap<>(before.settings().values());
        requested.forEach(
                (setting, value) -> after.put(setting, value.orElse(defaults.get(setting))));
        Optional<Rule> broken = brokenRule(after);
        if (broken.isPresent()) {
            return new UpdateOutcome.Inconsistent(broken.get());
        }

        List<ProductSettingStore.Write> writes = new ArrayList<>();
        requested.forEach(
                (setting, value) -> {
                    Origin origin = before.origins().get(setting);
                    boolean wasOverride = origin == Origin.OPERATOR;
                    BigDecimal previous = before.settings().value(setting);
                    // An unchanged value with an unchanged origin is no change to record; restoring
                    // the default also clears an override the reader ignored as invalid.
                    boolean changed =
                            value.isPresent()
                                    ? !wasOverride || previous.compareTo(value.get()) != 0
                                    : origin == Origin.OPERATOR
                                            || origin == Origin.INVALID_OVERRIDE;
                    if (changed) {
                        writes.add(
                                new ProductSettingStore.Write(
                                        setting.key(), value, previous, after.get(setting)));
                    }
                });
        if (writes.isEmpty()) {
            return new UpdateOutcome.Updated(overview(before));
        }
        Optional<Long> applied = store.apply(expectedRevision, writes, operatorId, clock.instant());
        if (applied.isEmpty()) {
            return new UpdateOutcome.Stale(store.read().revision());
        }
        return new UpdateOutcome.Updated(overview());
    }

    private record Resolution(
            ProductSettings settings,
            Map<ProductSetting, Origin> origins,
            Map<ProductSetting, Instant> changedAt) {}

    private Resolution resolve(ProductSettingStore.Stored stored) {
        EnumMap<ProductSetting, BigDecimal> values = new EnumMap<>(defaults);
        EnumMap<ProductSetting, Origin> origins = new EnumMap<>(ProductSetting.class);
        EnumMap<ProductSetting, Instant> changedAt = new EnumMap<>(ProductSetting.class);
        for (ProductSetting setting : ProductSetting.values()) {
            origins.put(setting, defaultOrigin(setting));
            ProductSettingStore.StoredValue override = stored.overrides().get(setting.key());
            if (override == null) {
                continue;
            }
            if (setting.admits(override.value())) {
                values.put(setting, override.value().stripTrailingZeros());
                origins.put(setting, Origin.OPERATOR);
                changedAt.put(setting, override.changedAt());
            } else {
                origins.put(setting, Origin.INVALID_OVERRIDE);
            }
        }
        // A stored combination that breaks a rule falls back to the defaults of that rule's
        // settings; the defaults satisfy every rule.
        for (Rule rule : Rule.values()) {
            if (!breaks(rule, values)) {
                continue;
            }
            for (ProductSetting setting : settingsOf(rule)) {
                if (origins.get(setting) == Origin.OPERATOR) {
                    origins.put(setting, Origin.INVALID_OVERRIDE);
                    changedAt.remove(setting);
                }
                values.put(setting, defaults.get(setting));
            }
        }
        return new Resolution(new ProductSettings(stored.revision(), values), origins, changedAt);
    }

    private static Set<ProductSetting> settingsOf(Rule rule) {
        return switch (rule) {
            case ATTENTION_PROVIDER_WEIGHT -> ATTENTION_PROVIDER_WEIGHTS;
            case INTEREST_THRESHOLDS_ASCENDING -> INTEREST_THRESHOLDS;
        };
    }

    private static Optional<Rule> brokenRule(Map<ProductSetting, BigDecimal> values) {
        for (Rule rule : Rule.values()) {
            if (breaks(rule, values)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    private static boolean breaks(Rule rule, Map<ProductSetting, BigDecimal> values) {
        return switch (rule) {
            case ATTENTION_PROVIDER_WEIGHT ->
                    ATTENTION_PROVIDER_WEIGHTS.stream()
                                    .map(values::get)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                                    .signum()
                            <= 0;
            case INTEREST_THRESHOLDS_ASCENDING -> {
                List<BigDecimal> thresholds =
                        List.of(
                                values.get(ProductSetting.CALENDAR_LOW_INTEREST_HYPES),
                                values.get(ProductSetting.CALENDAR_MODERATE_INTEREST_HYPES),
                                values.get(ProductSetting.CALENDAR_HIGH_INTEREST_HYPES),
                                values.get(ProductSetting.CALENDAR_VERY_HIGH_INTEREST_HYPES));
                boolean ascending = true;
                for (int index = 1; index < thresholds.size(); index++) {
                    ascending &= thresholds.get(index).compareTo(thresholds.get(index - 1)) > 0;
                }
                yield !ascending;
            }
        };
    }

    private static Origin defaultOrigin(ProductSetting setting) {
        return setting.fixedDefault().isPresent() ? Origin.DEFAULT : Origin.DEPLOYMENT;
    }

    private Overview overview(Resolution resolution) {
        ProductSettings settings = resolution.settings();
        List<Entry> entries = new ArrayList<>();
        for (ProductSetting setting : ProductSetting.values()) {
            entries.add(
                    new Entry(
                            setting,
                            settings.value(setting),
                            defaults.get(setting),
                            resolution.origins().get(setting),
                            Optional.ofNullable(resolution.changedAt().get(setting))));
        }
        List<RecentChange> recent =
                store.recentChanges(RECENT_CHANGES_LIMIT).stream()
                        .map(
                                change ->
                                        new RecentChange(
                                                change.revision(),
                                                ProductSetting.byKey(change.key()),
                                                change.key(),
                                                change.previous(),
                                                change.next(),
                                                change.restoredDefault(),
                                                change.changedAt()))
                        .toList();
        return new Overview(settings.revision(), entries, coverage(settings), recent);
    }

    /**
     * The configured acquisition horizon against the presentation windows. The scheduled policies
     * request {@code today + futureDays} as their last day, so that day is the horizon's end.
     */
    private Coverage coverage(ProductSettings settings) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), clock.getZone());
        int forwardDays = 0;
        if (acquisition.nearTermScheduled()) {
            forwardDays =
                    Math.max(
                            forwardDays,
                            settings.integer(ProductSetting.SYNCHRONIZATION_NEAR_TERM_FUTURE_DAYS));
        }
        if (acquisition.upcomingScheduled()) {
            forwardDays =
                    Math.max(
                            forwardDays,
                            settings.integer(ProductSetting.SYNCHRONIZATION_UPCOMING_FUTURE_DAYS));
        }
        LocalDate acquisitionEnd = today.plusDays(forwardDays);
        YearMonth featuredLast =
                YearMonth.from(today)
                        .plusMonths(settings.integer(ProductSetting.FEATURED_MONTHS_FORWARD));
        LocalDate upcomingEnd =
                UpcomingHorizon.end(
                        today, settings.integer(ProductSetting.UPCOMING_HIGHLIGHTS_HORIZON_MONTHS));
        return new Coverage(
                today,
                acquisition.enabled(),
                forwardDays,
                acquisitionEnd,
                featuredLast,
                featuredLast.atEndOfMonth().isAfter(acquisitionEnd),
                upcomingEnd,
                upcomingEnd.isAfter(acquisitionEnd));
    }
}
