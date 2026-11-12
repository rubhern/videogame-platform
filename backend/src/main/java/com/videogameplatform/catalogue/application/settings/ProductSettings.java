package com.videogameplatform.catalogue.application.settings;

import com.videogameplatform.catalogue.domain.AttentionWeights;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One coherent, validated set of effective product settings at one settings revision. A request
 * reads it once and uses it throughout, so an operator change never splits one operation between
 * an old and a new value. Every registered setting has a value.
 */
public record ProductSettings(long revision, Map<ProductSetting, BigDecimal> values) {

    public ProductSettings {
        if (revision < 0) {
            throw new IllegalArgumentException("A settings revision cannot be negative");
        }
        EnumMap<ProductSetting, BigDecimal> copy = new EnumMap<>(ProductSetting.class);
        copy.putAll(values);
        for (ProductSetting setting : ProductSetting.values()) {
            if (!setting.admits(copy.get(setting))) {
                throw new IllegalArgumentException("Invalid product setting " + setting.key());
            }
        }
        values = Collections.unmodifiableMap(copy);
        // Cross-setting invariants; the constructors throw on violation.
        attentionOf(values);
        interestBandsOf(values);
    }

    public BigDecimal value(ProductSetting setting) {
        return values.get(setting);
    }

    public int integer(ProductSetting setting) {
        return values.get(setting).intValueExact();
    }

    public AttentionWeights attention() {
        return attentionOf(values);
    }

    /** The calendar's four ascending daily total-Hype thresholds: low to very high interest. */
    public List<Long> interestBands() {
        return interestBandsOf(values);
    }

    public long calendarMinimumHypes() {
        return values.get(ProductSetting.CALENDAR_MINIMUM_HYPES).longValueExact();
    }

    private static AttentionWeights attentionOf(Map<ProductSetting, BigDecimal> values) {
        return new AttentionWeights(
                values.get(ProductSetting.ATTENTION_HYPES_WEIGHT).doubleValue(),
                values.get(ProductSetting.ATTENTION_WANT_TO_PLAY_WEIGHT).doubleValue(),
                values.get(ProductSetting.ATTENTION_VISITS_WEIGHT).doubleValue(),
                values.get(ProductSetting.ATTENTION_EDITORIAL_WEIGHT).doubleValue(),
                values.get(ProductSetting.ATTENTION_INHERITED_SHARE).doubleValue(),
                values.get(ProductSetting.ATTENTION_RE_RELEASE_SHARE).doubleValue());
    }

    private static List<Long> interestBandsOf(Map<ProductSetting, BigDecimal> values) {
        List<Long> bands =
                List.of(
                        values.get(ProductSetting.CALENDAR_LOW_INTEREST_HYPES).longValueExact(),
                        values.get(ProductSetting.CALENDAR_MODERATE_INTEREST_HYPES)
                                .longValueExact(),
                        values.get(ProductSetting.CALENDAR_HIGH_INTEREST_HYPES).longValueExact(),
                        values.get(ProductSetting.CALENDAR_VERY_HIGH_INTEREST_HYPES)
                                .longValueExact());
        for (int index = 1; index < bands.size(); index++) {
            if (bands.get(index) <= bands.get(index - 1)) {
                throw new IllegalArgumentException("Interest thresholds must strictly ascend");
            }
        }
        return bands;
    }
}
