package com.videogameplatform.catalogue.application;

/**
 * Release date as presented by any catalogue read.
 *
 * <p>The precision and its value stay together so presentation never exceeds what the
 * catalogue actually knows, and an unknown date stays explicitly unknown.
 */
public record CatalogueReleaseDate(Precision precision, String value) {

    public enum Precision {
        DAY,
        MONTH,
        QUARTER,
        YEAR,
        UNKNOWN
    }
}
