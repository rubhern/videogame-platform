package com.videogameplatform.catalogue.application.internal;

import com.videogameplatform.catalogue.application.CatalogueFreshness;
import com.videogameplatform.catalogue.application.CatalogueReleaseDate;
import com.videogameplatform.catalogue.application.CatalogueReleaseStatus;
import com.videogameplatform.catalogue.domain.FreshnessStatus;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;

/**
 * Translates domain values into the application vocabulary every catalogue read exposes.
 *
 * <p>Domain types stay inside the catalogue module; delivery only ever sees these results.
 */
public final class CatalogueReadMapping {

    private CatalogueReadMapping() {}

    public static CatalogueReleaseDate toReleaseDate(ReleaseDate source) {
        return switch (source) {
            case ReleaseDate.Day day ->
                    new CatalogueReleaseDate(CatalogueReleaseDate.Precision.DAY, day.value());
            case ReleaseDate.Month month ->
                    new CatalogueReleaseDate(CatalogueReleaseDate.Precision.MONTH, month.value());
            case ReleaseDate.Quarter quarter ->
                    new CatalogueReleaseDate(
                            CatalogueReleaseDate.Precision.QUARTER, quarter.value());
            case ReleaseDate.YearOnly year ->
                    new CatalogueReleaseDate(CatalogueReleaseDate.Precision.YEAR, year.value());
            case ReleaseDate.Unknown unknown ->
                    new CatalogueReleaseDate(CatalogueReleaseDate.Precision.UNKNOWN, null);
        };
    }

    public static CatalogueReleaseStatus toStatus(ReleaseStatus source) {
        return switch (source) {
            case ANNOUNCED -> CatalogueReleaseStatus.ANNOUNCED;
            case SCHEDULED -> CatalogueReleaseStatus.SCHEDULED;
            case RELEASED -> CatalogueReleaseStatus.RELEASED;
            case DELAYED -> CatalogueReleaseStatus.DELAYED;
            case CANCELLED -> CatalogueReleaseStatus.CANCELLED;
            case UNKNOWN -> CatalogueReleaseStatus.UNKNOWN;
        };
    }

    public static CatalogueFreshness toFreshness(FreshnessStatus source) {
        return switch (source) {
            case FRESH -> CatalogueFreshness.FRESH;
            case STALE -> CatalogueFreshness.STALE;
        };
    }
}
