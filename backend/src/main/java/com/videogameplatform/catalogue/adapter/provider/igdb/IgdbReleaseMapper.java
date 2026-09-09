package com.videogameplatform.catalogue.adapter.provider.igdb;

import com.videogameplatform.catalogue.adapter.provider.igdb.model.IgdbReleaseDatePayload;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRelease;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderMappingFailure;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderReleaseSignal;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates one IGDB release date into a product Release.
 *
 * <p>Three provider traps are handled explicitly here:
 *
 * <ul>
 *   <li><b>Precision.</b> IGDB states the precision it authored. A day is only a day when IGDB says
 *       so; a quarter, month or year never becomes a day, and to-be-announced stays unknown.
 *   <li><b>Time zones.</b> The authored calendar fields win over the Unix timestamp, which is only
 *       interpreted at UTC. Neither the host default zone nor the product presentation zone may
 *       move a release into another day.
 *   <li><b>Platform identity.</b> Only an allowlisted provider platform becomes a product platform.
 *       Generic PC never absorbs DOS, Linux or Mac, because that would merge distinct commercial
 *       releases (REL-005).
 * </ul>
 */
final class IgdbReleaseMapper {

    private IgdbReleaseMapper() {}

    /** Either a usable Release or the bounded reason it cannot be mapped. */
    record Mapped(Optional<ProviderRelease> release, Optional<ProviderMappingFailure> failure) {

        private static Mapped of(ProviderRelease release) {
            return new Mapped(Optional.of(release), Optional.empty());
        }

        private static Mapped failed(ProviderMappingFailure failure) {
            return new Mapped(Optional.empty(), Optional.of(failure));
        }
    }

    static Mapped map(IgdbReleaseDatePayload payload, String gameStatus, IgdbApiSettings settings) {
        if (payload == null
                || payload.id() == null
                || payload.id() <= 0
                || payload.platform() == null) {
            return Mapped.failed(ProviderMappingFailure.RECORD_UNREADABLE);
        }
        String platformCode = platformCode(payload, settings);
        if (platformCode == null) {
            return Mapped.failed(ProviderMappingFailure.PLATFORM_NOT_SUPPORTED);
        }
        String regionCode = regionCode(payload, settings);
        if (regionCode == null) {
            return Mapped.failed(ProviderMappingFailure.REGION_NOT_SUPPORTED);
        }
        Optional<ReleaseDate> date = releaseDate(payload);
        if (date.isEmpty()) {
            return Mapped.failed(ProviderMappingFailure.RELEASE_DATE_INVALID);
        }
        return Mapped.of(
                new ProviderRelease(
                        Long.toString(payload.id()),
                        platformCode,
                        regionCode,
                        date.orElseThrow(),
                        signal(payload, gameStatus)));
    }

    private static String platformCode(IgdbReleaseDatePayload payload, IgdbApiSettings settings) {
        String slug = normalize(payload.platform().slug());
        String code = settings.platformCodes().get(slug);
        return code != null
                ? code
                : settings.platformCodes().get(normalize(payload.platform().name()));
    }

    private static String regionCode(IgdbReleaseDatePayload payload, IgdbApiSettings settings) {
        if (payload.releaseRegion() == null || payload.releaseRegion().value().isBlank()) {
            // REL-002: a release states one region or an explicit unknown; it never guesses one.
            return settings.unknownRegionCode();
        }
        return settings.regionCodes().get(normalize(payload.releaseRegion().value()));
    }

    private static ProviderReleaseSignal signal(IgdbReleaseDatePayload payload, String gameStatus) {
        String combined =
                ((payload.status() == null ? "" : payload.status().value()) + " " + gameStatus)
                        .toLowerCase(Locale.ROOT);
        if (combined.contains("cancel")) {
            return ProviderReleaseSignal.CANCELLED;
        }
        if (combined.contains("delay")) {
            return ProviderReleaseSignal.DELAYED;
        }
        return ProviderReleaseSignal.NONE;
    }

    private static Optional<ReleaseDate> releaseDate(IgdbReleaseDatePayload payload) {
        String format =
                payload.dateFormat() == null
                        ? ""
                        : payload.dateFormat().value().trim().toUpperCase(Locale.ROOT);
        if (format.contains("TBD")) {
            return Optional.of(new ReleaseDate.Unknown());
        }
        Matcher quarter = QUARTER_FORMAT.matcher(format);
        if (quarter.find()) {
            return quarterDate(payload, Integer.parseInt(quarter.group(1)));
        }
        if ("YYYY".equals(format)) {
            return yearDate(payload);
        }
        if (format.endsWith("DD")) {
            return dayDate(payload);
        }
        if (format.startsWith("YYYYMM")) {
            return monthDate(payload);
        }
        // The provider stated no precision: fall back to the most precise authored calendar fields.
        if (payload.d() != null) {
            return dayDate(payload);
        }
        if (payload.m() != null) {
            return monthDate(payload);
        }
        if (payload.y() != null) {
            return yearDate(payload);
        }
        return payload.date() == null ? Optional.of(new ReleaseDate.Unknown()) : dayDate(payload);
    }

    private static Optional<ReleaseDate> dayDate(IgdbReleaseDatePayload payload) {
        LocalDate day = day(payload);
        return day == null ? Optional.empty() : safely(() -> new ReleaseDate.Day(day));
    }

    private static Optional<ReleaseDate> monthDate(IgdbReleaseDatePayload payload) {
        Integer year = year(payload);
        Integer month = month(payload);
        return year == null || month == null
                ? Optional.empty()
                : safely(() -> new ReleaseDate.Month(YearMonth.of(year, month)));
    }

    private static Optional<ReleaseDate> quarterDate(IgdbReleaseDatePayload payload, int quarter) {
        Integer year = year(payload);
        return year == null
                ? Optional.empty()
                : safely(() -> new ReleaseDate.Quarter(year, quarter));
    }

    private static Optional<ReleaseDate> yearDate(IgdbReleaseDatePayload payload) {
        Integer year = year(payload);
        return year == null
                ? Optional.empty()
                : safely(() -> new ReleaseDate.YearOnly(Year.of(year)));
    }

    /**
     * The authored calendar fields are timezone-free evidence and win. The Unix timestamp is a
     * fallback and is read at UTC, so a release never shifts into an adjacent day.
     */
    private static LocalDate day(IgdbReleaseDatePayload payload) {
        if (payload.y() != null && payload.m() != null && payload.d() != null) {
            try {
                return LocalDate.of(payload.y(), payload.m(), payload.d());
            } catch (RuntimeException exception) {
                return null;
            }
        }
        return payload.date() == null
                ? null
                : Instant.ofEpochSecond(payload.date()).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static Integer year(IgdbReleaseDatePayload payload) {
        if (payload.y() != null) {
            return payload.y();
        }
        return payload.date() == null
                ? null
                : Instant.ofEpochSecond(payload.date()).atZone(ZoneOffset.UTC).getYear();
    }

    private static Integer month(IgdbReleaseDatePayload payload) {
        if (payload.m() != null) {
            return payload.m();
        }
        return payload.date() == null
                ? null
                : Instant.ofEpochSecond(payload.date()).atZone(ZoneOffset.UTC).getMonthValue();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Optional<ReleaseDate> safely(Supplier<ReleaseDate> supplier) {
        try {
            return Optional.of(supplier.get());
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static final Pattern QUARTER_FORMAT = Pattern.compile("Q([1-4])");
}
