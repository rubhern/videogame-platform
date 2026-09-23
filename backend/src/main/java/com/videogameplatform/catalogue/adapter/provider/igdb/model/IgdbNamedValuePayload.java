package com.videogameplatform.catalogue.adapter.provider.igdb.model;

/**
 * IGDB expands several enumerations into a small object with one descriptive field.
 *
 * <p>Release status, game status, game type, date format and release region all use this shape, so
 * one transport record covers them without inventing five.
 */
public record IgdbNamedValuePayload(
        String name, String status, String type, String format, String region) {

    /** The single descriptive value, whichever field the expanded enumeration used. */
    public String value() {
        for (String candidate : new String[] {name, status, type, format, region}) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }
}
