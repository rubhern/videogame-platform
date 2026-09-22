package com.videogameplatform.catalogue.application.releases.port;

import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase;
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
            List<String> platformIds,
            List<String> regionIds,
            Pagination pagination,
            boolean includeUnknownUpcomingDates,
            int releaseGroupLimit) {

        public Criteria {
            platformIds = platformIds == null ? List.of() : List.copyOf(platformIds);
            regionIds = regionIds == null ? List.of() : List.copyOf(regionIds);
            if (releaseGroupLimit < 1) {
                throw new IllegalArgumentException(
                        "Release group must be bounded to at least 1 release per game");
            }
        }
    }

    record Window(LocalDate from, LocalDate to) {}

    record Pagination(int pageNumber, int pageSize, long offset) {}

    record Result(
            String publicationVersion,
            List<Taxonomy> platforms,
            List<Taxonomy> regions,
            List<Item> items,
            long totalItems) {}

    record Taxonomy(String id, String name) {}

    /** One game with the bounded, view-and-filter-matching releases grouped underneath it. */
    record Item(
            String gameId,
            String slug,
            String canonicalTitle,
            CatalogueCoverReference cover,
            List<ReleaseRow> releases) {}

    /** One preserved release; grouping never merges or rewrites its identity or fields. */
    record ReleaseRow(
            String releaseId,
            String gameId,
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
}
