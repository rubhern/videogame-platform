package com.videogameplatform.catalogue.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Provider-independent result of browsing the current local release snapshot. */
public record ReleasePage(
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

    public sealed interface Cover permits ProviderCoverReference, FallbackCover {
        String alternativeText();
    }

    public record ProviderCoverReference(
            String provider, String reference, String alternativeText, String sourceUrl)
            implements Cover {}

    public record FallbackCover(String assetPath, String alternativeText) implements Cover {}

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
        ANNOUNCED("announced"),
        SCHEDULED("scheduled"),
        RELEASED("released"),
        DELAYED("delayed"),
        CANCELLED("cancelled"),
        UNKNOWN("unknown");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public enum Source {
        EXTERNAL_PROVIDER("external_provider"),
        PRODUCT_CURATED("product_curated"),
        OFFICIAL_SOURCE("official_source");

        private final String value;

        Source(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public enum Verification {
        PROVIDER_ONLY("provider_only"),
        VERIFIED("verified");

        private final String value;

        Verification(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public enum Review {
        NOT_REQUIRED("not_required"),
        REQUIRED("required");

        private final String value;

        Review(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public enum Freshness {
        FRESH("fresh"),
        STALE("stale");

        private final String value;

        Freshness(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
