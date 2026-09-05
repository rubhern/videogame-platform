package com.videogameplatform.catalogue.application.releases;

import com.videogameplatform.catalogue.application.CatalogueFreshness;
import com.videogameplatform.catalogue.application.CatalogueReleaseDate;
import com.videogameplatform.catalogue.application.CatalogueReleaseStatus;
import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Provider-independent result of browsing the current local release snapshot. */
public record BrowseReleasesResult(
        String publicationVersion,
        BrowseReleasesUseCase.View view,
        LocalDate evaluatedOn,
        Window window,
        ActiveFilters activeFilters,
        AvailableFilters availableFilters,
        List<Item> items,
        PageMetadata page) {

    public record Window(LocalDate from, LocalDate to) {}

    public record ActiveFilters(String platformId, String regionId) {}

    public record AvailableFilters(List<Taxonomy> platforms, List<Taxonomy> regions) {}

    public record Taxonomy(String id, String name) {}

    public record Item(
            String gameId,
            String slug,
            String canonicalTitle,
            CatalogueCover primaryCover,
            Release release) {}

    public record Release(
            String releaseId,
            String gameId,
            Taxonomy platform,
            Taxonomy region,
            CatalogueReleaseDate releaseDate,
            CatalogueReleaseStatus status,
            Provenance provenance,
            Instant providerUpdatedAt,
            Instant lastSyncedAt,
            Instant lastVerifiedAt,
            Verification verificationLevel,
            Review reviewStatus,
            CatalogueFreshness freshnessStatus) {}

    public record Provenance(Source sourceKind, String sourceName, String sourceEntityType) {}

    public record PageMetadata(int number, int size, long totalItems, long totalPages) {}

    public enum Source {
        EXTERNAL_PROVIDER,
        PRODUCT_CURATED,
        OFFICIAL_SOURCE
    }

    public enum Verification {
        PROVIDER_ONLY,
        VERIFIED
    }

    public enum Review {
        NOT_REQUIRED,
        REQUIRED
    }
}
