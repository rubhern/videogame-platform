package com.videogameplatform.catalogue.application.administration;

import com.videogameplatform.catalogue.application.settings.ProductSetting;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * The catalogue operator's product settings (UC-024, #450): the effective value of every registered
 * setting with its default, range and origin, how the presentation windows relate to the configured
 * acquisition horizon, the recent changes, and a validated update guarded by the settings revision.
 *
 * <p>An update names the revision it was prepared from. Every change in it applies, or none does:
 * an unknown key, a value outside its range, a combination that breaks a cross-setting rule or a
 * revision that has moved on rejects the whole update. Nothing here calls the provider, reschedules
 * acquisition or changes infrastructure; a wider synchronization window applies from the next
 * scheduled run.
 */
public interface ManageProductSettings {

    /** The most changes an overview lists, newest first. */
    int RECENT_CHANGES_LIMIT = 30;

    /** The most changes one update may carry: one per registered setting. */
    int MAX_CHANGES = ProductSetting.values().length;

    Overview overview();

    /**
     * Applies {@code changes} prepared from {@code expectedRevision} on behalf of the authenticated
     * operator {@code operatorId}, a server-derived product identity.
     */
    UpdateOutcome update(long expectedRevision, List<Change> changes, String operatorId);

    /** A new value for {@code key}, or its default when {@code value} is empty. */
    record Change(String key, Optional<BigDecimal> value) {
        public Change {
            value = value == null ? Optional.empty() : value;
        }
    }

    record Overview(
            long revision,
            List<Entry> settings,
            Coverage coverage,
            List<RecentChange> recentChanges) {
        public Overview {
            settings = List.copyOf(settings);
            recentChanges = List.copyOf(recentChanges);
        }
    }

    /**
     * {@code value} is effective now. {@code changedAt} is when the operator last set it, present
     * only for {@link Origin#OPERATOR}.
     */
    record Entry(
            ProductSetting setting,
            BigDecimal value,
            BigDecimal defaultValue,
            Origin origin,
            Optional<Instant> changedAt) {}

    enum Origin {
        /** The approved product default. */
        DEFAULT,
        /** The deployment's configured value, which an operator has not overridden. */
        DEPLOYMENT,
        /** An operator's stored value. */
        OPERATOR,
        /** A stored value no longer valid, ignored in favour of the default. */
        INVALID_OVERRIDE
    }

    /**
     * How far presentation reaches compared with the configured acquisition horizon, evaluated in
     * {@code Europe/Madrid}. {@code acquisitionForwardDays} is the widest future window of the
     * scheduled policies that are not disabled, zero when none is; {@code schedulingEnabled}
     * states whether this deployment runs them at all. A window beyond the horizon shows periods
     * the scheduled acquisition never reaches: nothing fills them automatically.
     */
    record Coverage(
            LocalDate evaluatedOn,
            boolean schedulingEnabled,
            int acquisitionForwardDays,
            LocalDate acquisitionEnd,
            YearMonth featuredLastMonth,
            boolean featuredBeyondAcquisition,
            LocalDate upcomingHorizonEnd,
            boolean upcomingBeyondAcquisition) {}

    record RecentChange(
            long revision,
            Optional<ProductSetting> setting,
            String key,
            BigDecimal previous,
            BigDecimal next,
            boolean restoredDefault,
            Instant changedAt) {}

    sealed interface UpdateOutcome {
        record Updated(Overview overview) implements UpdateOutcome {}

        /** Another change came first; the update was prepared from an older revision. */
        record Stale(long currentRevision) implements UpdateOutcome {}

        /** The change at {@code index} names no registered setting, or names one twice. */
        record UnknownSetting(int index) implements UpdateOutcome {}

        /** The change at {@code index} is outside its setting's kind or range. */
        record ValueInvalid(int index) implements UpdateOutcome {}

        /** The resulting settings break a cross-setting rule. */
        record Inconsistent(Rule rule) implements UpdateOutcome {}

        /** Too many changes, or none. */
        record ChangesInvalid() implements UpdateOutcome {}
    }

    enum Rule {
        /** At least one of the Hypes, Want to Play and Visits weights must be positive. */
        ATTENTION_PROVIDER_WEIGHT,
        /** The calendar interest thresholds must strictly ascend from low to very high. */
        INTEREST_THRESHOLDS_ASCENDING
    }
}
