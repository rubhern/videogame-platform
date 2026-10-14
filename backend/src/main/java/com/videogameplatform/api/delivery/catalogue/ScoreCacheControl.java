package com.videogameplatform.api.delivery.catalogue;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Public pages that carry scores may be stored, but always revalidate before reuse: scores change
 * independently of the catalogue. Older deployments may still configure a catalogue-only
 * freshness window, which is dropped here rather than trusted.
 */
public final class ScoreCacheControl {
    private ScoreCacheControl() {}

    public static String revalidating(String configured) {
        if (configured == null || configured.isBlank()) return configured;
        String kept =
                Arrays.stream(configured.split(","))
                        .map(String::trim)
                        .filter(
                                value ->
                                        !value.matches(
                                                "(?i)(?:s-maxage|max-age|stale-while-revalidate|stale-if-error)\\s*=.*"))
                        .collect(Collectors.joining(", "));
        if (Arrays.asList(kept.split(",\\s*")).contains("no-cache")) return kept;
        return kept.isBlank() ? "public, no-cache" : kept + ", no-cache";
    }
}
