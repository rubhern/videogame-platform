package com.videogameplatform.catalogue.application;

import java.net.URI;
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
            Cover primaryCover,
            Release release) {}

    public sealed interface Cover permits ProviderCover, ProductCover, UnavailableCover {}

    public record ProviderCover(URI url, String alternativeText, Attribution attribution)
            implements Cover {}

    public record Attribution(String label, URI sourceUrl) {}

    public record ProductCover(String assetPath, String alternativeText) implements Cover {}

    public record UnavailableCover() implements Cover {}

    public record Release(
            String releaseId,
            String gameId,
            Taxonomy platform,
            Taxonomy region,
            DateValue releaseDate,
            Status status,
            Provenance provenance,
            Instant providerUpdatedAt,
            Instant lastSyncedAt,
            Instant lastVerifiedAt,
            Verification verificationLevel,
            Review reviewStatus,
            Freshness freshnessStatus) {}

    public record DateValue(DatePrecision precision, String value) {}

    public record Provenance(Source sourceKind, String sourceName, String sourceEntityType) {}

    public record PageMetadata(int number, int size, long totalItems, long totalPages) {}

    public enum DatePrecision {
        DAY,
        MONTH,
        QUARTER,
        YEAR,
        UNKNOWN
    }

    public enum Status {
        ANNOUNCED,
        SCHEDULED,
        RELEASED,
        DELAYED,
        CANCELLED,
        UNKNOWN
    }

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

    public enum Freshness {
        FRESH,
        STALE
    }
}
