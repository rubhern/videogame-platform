package com.videogameplatform.catalogue.application.port;

import com.videogameplatform.catalogue.application.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.SourceKind;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Outbound query port for one bounded page from the current catalogue publication. */
public interface ReleaseBrowseReadPort {

    Optional<Result> findPublishedReleases(Criteria criteria);

    record Criteria(
            BrowseReleasesUseCase.View view,
            Window window,
            String platformId,
            String regionId,
            Pagination pagination,
            boolean includeUnknownUpcomingDates) {}

    record Window(LocalDate from, LocalDate to) {}

    record Pagination(int pageNumber, int pageSize, long offset) {}

    record Result(
            String publicationVersion,
            List<Taxonomy> platforms,
            List<Taxonomy> regions,
            List<Item> items,
            long totalItems) {}

    record Taxonomy(String id, String name) {}

    record Item(
            String releaseId,
            String gameId,
            String slug,
            String canonicalTitle,
            CoverReference cover,
            Taxonomy platform,
            Taxonomy region,
            ReleaseDate releaseDate,
            ReleaseStatus status,
            SourceKind sourceKind,
            String sourceName,
            String sourceEntityType,
            Instant providerUpdatedAt,
            Instant lastSyncedAt,
            Instant lastVerifiedAt,
            VerificationLevel verificationLevel,
            ReviewStatus reviewStatus) {}

    sealed interface CoverReference
            permits ProductCoverReference, ProviderCoverReference, UnavailableCoverReference {
        String alternativeText();
    }

    record ProductCoverReference(String assetPath, String alternativeText)
            implements CoverReference {}

    record ProviderCoverReference(
            String provider, String reference, String alternativeText, String sourceUrl)
            implements CoverReference {}

    record UnavailableCoverReference(String alternativeText) implements CoverReference {}
}
