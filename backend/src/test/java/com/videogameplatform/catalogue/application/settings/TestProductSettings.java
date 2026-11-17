package com.videogameplatform.catalogue.application.settings;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * Product settings for tests: the approved defaults, with the synchronization windows of the
 * application.yaml defaults, which a test may change between operations as an operator would.
 */
public final class TestProductSettings implements CurrentProductSettings {

    private final EnumMap<ProductSetting, BigDecimal> values = new EnumMap<>(ProductSetting.class);
    private long revision;

    private TestProductSettings() {
        for (ProductSetting setting : ProductSetting.values()) {
            values.put(setting, setting.fixedDefault().orElse(null));
        }
        values.putAll(deploymentDefaults());
    }

    public static TestProductSettings defaults() {
        return new TestProductSettings();
    }

    /** The application.yaml defaults of the scheduled synchronization windows. */
    public static Map<ProductSetting, BigDecimal> deploymentDefaults() {
        return Map.of(
                ProductSetting.SYNCHRONIZATION_NEAR_TERM_PAST_DAYS, BigDecimal.valueOf(28),
                ProductSetting.SYNCHRONIZATION_NEAR_TERM_FUTURE_DAYS, BigDecimal.valueOf(28),
                ProductSetting.SYNCHRONIZATION_UPCOMING_PAST_DAYS, BigDecimal.ZERO,
                ProductSetting.SYNCHRONIZATION_UPCOMING_FUTURE_DAYS, BigDecimal.valueOf(180));
    }

    /** The defaults with the given synchronization windows, as the configured policies give. */
    public static TestProductSettings windows(
            int nearTermPast, int nearTermFuture, int upcomingPast, int upcomingFuture) {
        return defaults()
                .set(ProductSetting.SYNCHRONIZATION_NEAR_TERM_PAST_DAYS, nearTermPast)
                .set(ProductSetting.SYNCHRONIZATION_NEAR_TERM_FUTURE_DAYS, nearTermFuture)
                .set(ProductSetting.SYNCHRONIZATION_UPCOMING_PAST_DAYS, upcomingPast)
                .set(ProductSetting.SYNCHRONIZATION_UPCOMING_FUTURE_DAYS, upcomingFuture);
    }

    /** Sets one value, as an accepted operator change would, and advances the revision. */
    public TestProductSettings set(ProductSetting setting, Number value) {
        values.put(setting, new BigDecimal(value.toString()));
        revision++;
        return this;
    }

    @Override
    public ProductSettings current() {
        return new ProductSettings(revision, values);
    }
}
