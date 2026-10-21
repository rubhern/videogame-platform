package com.videogameplatform.catalogue.application.releases.internal;

import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import com.videogameplatform.catalogue.application.releases.ReleaseQueryValidationException;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.Taxonomy;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The multi-select platform and region filters the release explorer (UC-001) and the release
 * calendar (UC-016) share: their normalization, their validation against the returned facets and
 * the presented facet order.
 */
final class ReleaseFilters {

    private static final Comparator<Taxonomy> ORDER =
            Comparator.comparing((Taxonomy value) -> value.name().toLowerCase(Locale.ROOT))
                    .thenComparing(Taxonomy::id);

    private ReleaseFilters() {}

    /**
     * Deterministic normalization for multi-select filters: trim, drop blanks, de-duplicate and
     * sort, so repeated values collapse and the applied filter is stable and reproducible.
     */
    static List<String> normalize(List<String> values) {
        return values.stream()
                .filter(value -> value != null)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * A selected value is valid only if it is a real taxonomy id, which the read port keeps
     * representable in the returned facets. Any unknown id fails the whole request.
     */
    static void validate(
            List<String> platformIds,
            List<String> regionIds,
            List<Taxonomy> platforms,
            List<Taxonomy> regions) {
        if (!platformIds.stream().allMatch(requested -> contains(platforms, requested))) {
            throw new ReleaseQueryValidationException(
                    ReleaseQueryValidationException.Code.PLATFORM_NOT_SUPPORTED);
        }
        if (!regionIds.stream().allMatch(requested -> contains(regions, requested))) {
            throw new ReleaseQueryValidationException(
                    ReleaseQueryValidationException.Code.REGION_NOT_SUPPORTED);
        }
    }

    static BrowseReleasesResult.AvailableFilters available(
            List<Taxonomy> platforms, List<Taxonomy> regions) {
        return new BrowseReleasesResult.AvailableFilters(presented(platforms), presented(regions));
    }

    private static boolean contains(List<Taxonomy> values, String id) {
        return values.stream().anyMatch(value -> value.id().equals(id));
    }

    private static List<BrowseReleasesResult.Taxonomy> presented(List<Taxonomy> values) {
        return values.stream()
                .sorted(ORDER)
                .map(value -> new BrowseReleasesResult.Taxonomy(value.id(), value.name()))
                .toList();
    }
}
