package com.videogameplatform.catalogue.application.settings;

import com.videogameplatform.catalogue.application.rankings.RankGamesUseCase;
import com.videogameplatform.catalogue.application.releases.ReleaseHighlightsPolicy;
import com.videogameplatform.catalogue.domain.AttentionWeights;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

/**
 * The closed registry of operator-editable product settings (#450, UC-024). Each setting has a
 * stable key, an owning group, a value kind, an inclusive validation range and an approved default.
 * Nothing outside this list is editable: secrets, endpoints, credentials, schedules, deployment and
 * telemetry settings and safety bounds stay in external configuration.
 *
 * <p>The synchronization windows default to the deployment's configured scheduling policy rather
 * than to a fixed value, so an operator override is the only difference the database introduces.
 */
public enum ProductSetting {
    FEATURED_WEEKS_BACK("featured.weeks-back", Group.FEATURED, Kind.INTEGER, 0, 104, 16),
    FEATURED_WEEKS_FORWARD("featured.weeks-forward", Group.FEATURED, Kind.INTEGER, 0, 104, 16),
    FEATURED_MONTHS_BACK("featured.months-back", Group.FEATURED, Kind.INTEGER, 0, 36, 10),
    FEATURED_MONTHS_FORWARD("featured.months-forward", Group.FEATURED, Kind.INTEGER, 0, 24, 5),
    UPCOMING_HIGHLIGHTS_HORIZON_MONTHS(
            "upcoming-highlights.horizon-months",
            Group.UPCOMING_HIGHLIGHTS,
            Kind.INTEGER,
            1,
            24,
            6),
    ATTENTION_HYPES_WEIGHT(
            "attention.hypes-weight",
            Group.ATTENTION,
            Kind.DECIMAL,
            0,
            10,
            AttentionWeights.DEFAULT.hypes()),
    ATTENTION_WANT_TO_PLAY_WEIGHT(
            "attention.want-to-play-weight",
            Group.ATTENTION,
            Kind.DECIMAL,
            0,
            10,
            AttentionWeights.DEFAULT.wantToPlay()),
    ATTENTION_VISITS_WEIGHT(
            "attention.visits-weight",
            Group.ATTENTION,
            Kind.DECIMAL,
            0,
            10,
            AttentionWeights.DEFAULT.visits()),
    ATTENTION_EDITORIAL_WEIGHT(
            "attention.editorial-weight",
            Group.ATTENTION,
            Kind.DECIMAL,
            0,
            1,
            AttentionWeights.DEFAULT.editorial()),
    ATTENTION_INHERITED_SHARE(
            "attention.inherited-share",
            Group.ATTENTION,
            Kind.DECIMAL,
            0,
            1,
            AttentionWeights.DEFAULT.inheritedShare()),
    ATTENTION_RE_RELEASE_SHARE(
            "attention.re-release-share",
            Group.ATTENTION,
            Kind.DECIMAL,
            0,
            1,
            AttentionWeights.DEFAULT.reReleaseShare()),
    RANKINGS_IGDB_MINIMUM_RATINGS(
            "rankings.igdb-minimum-ratings",
            Group.RANKINGS,
            Kind.INTEGER,
            1,
            100_000,
            RankGamesUseCase.Source.IGDB_PLAYERS.minimumRatings()),
    RANKINGS_COMMUNITY_MINIMUM_RATINGS(
            "rankings.community-minimum-ratings",
            Group.RANKINGS,
            Kind.INTEGER,
            1,
            100_000,
            RankGamesUseCase.Source.GAMEOMETRO_COMMUNITY.minimumRatings()),
    CALENDAR_MINIMUM_HYPES(
            "calendar.minimum-hypes",
            Group.CALENDAR,
            Kind.INTEGER,
            1,
            1_000_000,
            ReleaseHighlightsPolicy.MINIMUM_HYPES),
    CALENDAR_DAY_PREVIEW_SIZE("calendar.day-preview-size", Group.CALENDAR, Kind.INTEGER, 1, 10, 3),
    CALENDAR_LOW_INTEREST_HYPES(
            "calendar.low-interest-hypes", Group.CALENDAR, Kind.INTEGER, 1, 10_000_000, 10),
    CALENDAR_MODERATE_INTEREST_HYPES(
            "calendar.moderate-interest-hypes", Group.CALENDAR, Kind.INTEGER, 1, 10_000_000, 30),
    CALENDAR_HIGH_INTEREST_HYPES(
            "calendar.high-interest-hypes", Group.CALENDAR, Kind.INTEGER, 1, 10_000_000, 100),
    CALENDAR_VERY_HIGH_INTEREST_HYPES(
            "calendar.very-high-interest-hypes", Group.CALENDAR, Kind.INTEGER, 1, 10_000_000, 150),
    SYNCHRONIZATION_NEAR_TERM_PAST_DAYS(
            "synchronization.near-term.past-days", Group.SYNCHRONIZATION, Kind.INTEGER, 0, 366),
    SYNCHRONIZATION_NEAR_TERM_FUTURE_DAYS(
            "synchronization.near-term.future-days", Group.SYNCHRONIZATION, Kind.INTEGER, 0, 366),
    SYNCHRONIZATION_UPCOMING_PAST_DAYS(
            "synchronization.upcoming.past-days", Group.SYNCHRONIZATION, Kind.INTEGER, 0, 366),
    SYNCHRONIZATION_UPCOMING_FUTURE_DAYS(
            "synchronization.upcoming.future-days", Group.SYNCHRONIZATION, Kind.INTEGER, 0, 730);

    /** The product area a setting belongs to; the admin panel groups settings by it. */
    public enum Group {
        FEATURED,
        UPCOMING_HIGHLIGHTS,
        ATTENTION,
        RANKINGS,
        CALENDAR,
        SYNCHRONIZATION
    }

    /** Whole numbers, or decimals of at most {@value ProductSetting#DECIMAL_SCALE} places. */
    public enum Kind {
        INTEGER,
        DECIMAL
    }

    /** Stored values keep this many decimals; the database column has the same scale. */
    public static final int DECIMAL_SCALE = 4;

    private final String key;
    private final Group group;
    private final Kind kind;
    private final BigDecimal minimum;
    private final BigDecimal maximum;
    private final BigDecimal fixedDefault;

    ProductSetting(String key, Group group, Kind kind, long minimum, long maximum, double value) {
        this(key, group, kind, minimum, maximum, new BigDecimal(Double.toString(value)));
    }

    ProductSetting(String key, Group group, Kind kind, long minimum, long maximum, long value) {
        this(key, group, kind, minimum, maximum, BigDecimal.valueOf(value));
    }

    /** A setting whose default is the deployment's configuration. */
    ProductSetting(String key, Group group, Kind kind, long minimum, long maximum) {
        this(key, group, kind, minimum, maximum, (BigDecimal) null);
    }

    ProductSetting(
            String key,
            Group group,
            Kind kind,
            long minimum,
            long maximum,
            BigDecimal fixedDefault) {
        this.key = key;
        this.group = group;
        this.kind = kind;
        this.minimum = BigDecimal.valueOf(minimum);
        this.maximum = BigDecimal.valueOf(maximum);
        this.fixedDefault = fixedDefault == null ? null : fixedDefault.stripTrailingZeros();
    }

    public String key() {
        return key;
    }

    public Group group() {
        return group;
    }

    public Kind kind() {
        return kind;
    }

    public BigDecimal minimum() {
        return minimum;
    }

    public BigDecimal maximum() {
        return maximum;
    }

    /** The approved default, or empty when the deployment's configuration supplies it. */
    public Optional<BigDecimal> fixedDefault() {
        return Optional.ofNullable(fixedDefault);
    }

    /** Whether {@code value} has this setting's kind and lies inside its inclusive range. */
    public boolean admits(BigDecimal value) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            return false;
        }
        BigDecimal normalized = value.stripTrailingZeros();
        return kind == Kind.INTEGER ? normalized.scale() <= 0 : normalized.scale() <= DECIMAL_SCALE;
    }

    public static Optional<ProductSetting> byKey(String key) {
        return Arrays.stream(values()).filter(setting -> setting.key.equals(key)).findFirst();
    }
}
