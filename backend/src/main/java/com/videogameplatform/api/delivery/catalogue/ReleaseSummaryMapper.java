package com.videogameplatform.api.delivery.catalogue;

import com.videogameplatform.api.generated.model.DayReleaseDate;
import com.videogameplatform.api.generated.model.MonthReleaseDate;
import com.videogameplatform.api.generated.model.Platform;
import com.videogameplatform.api.generated.model.QuarterReleaseDate;
import com.videogameplatform.api.generated.model.Region;
import com.videogameplatform.api.generated.model.ReleaseDate;
import com.videogameplatform.api.generated.model.ReleaseSummary;
import com.videogameplatform.api.generated.model.UnknownReleaseDate;
import com.videogameplatform.api.generated.model.YearReleaseDate;
import com.videogameplatform.catalogue.application.CatalogueReleaseDate;
import com.videogameplatform.catalogue.application.CatalogueReleaseStatus;
import java.time.LocalDate;

/**
 * Maps one presented release of a platform scope to the contract's {@code ReleaseSummary}, as
 * Explorar catálogo (UC-019) and a platform TOPómetro (UC-018) present it.
 */
public final class ReleaseSummaryMapper {

    private ReleaseSummaryMapper() {}

    public static ReleaseSummary toResponse(
            Platform platform,
            Region region,
            CatalogueReleaseDate releaseDate,
            CatalogueReleaseStatus status) {
        return new ReleaseSummary(platform, region, toReleaseDate(releaseDate), toStatus(status));
    }

    private static ReleaseDate toReleaseDate(CatalogueReleaseDate source) {
        return switch (source.precision()) {
            case DAY -> new DayReleaseDate("day", LocalDate.parse(source.value()));
            case MONTH -> new MonthReleaseDate("month", source.value());
            case QUARTER -> new QuarterReleaseDate("quarter", source.value());
            case YEAR -> new YearReleaseDate("year", source.value());
            case UNKNOWN -> new UnknownReleaseDate("unknown", null);
        };
    }

    private static ReleaseSummary.StatusEnum toStatus(CatalogueReleaseStatus source) {
        return switch (source) {
            case ANNOUNCED -> ReleaseSummary.StatusEnum.ANNOUNCED;
            case SCHEDULED -> ReleaseSummary.StatusEnum.SCHEDULED;
            case RELEASED -> ReleaseSummary.StatusEnum.RELEASED;
            case DELAYED -> ReleaseSummary.StatusEnum.DELAYED;
            case CANCELLED -> ReleaseSummary.StatusEnum.CANCELLED;
            case UNKNOWN -> ReleaseSummary.StatusEnum.UNKNOWN;
        };
    }
}
