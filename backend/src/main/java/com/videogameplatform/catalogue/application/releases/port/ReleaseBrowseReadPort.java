package com.videogameplatform.catalogue.application.releases.port;

import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStage;
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

    /**
     * One page over an inclusive range. {@code evaluatedOn} splits the range: up to and including
     * it, a release must have happened; after it, a release is expected. {@code
     * includeApproximateDates} admits month, quarter and year periods that have not ended and
     * reach the range from {@code evaluatedOn} on; unknown (TBA) dates never join a range.
     */
    record Criteria(
            Window window,
            LocalDate evaluatedOn,
            BrowseReleasesUseCase.Sort sort,
            List<String> platformIds,
            List<String> regionIds,
            Pagination pagination,
            boolean includeApproximateDates,
            int releaseGroupLimit) {

        public Criteria {
            if (window == null || evaluatedOn == null || sort == null) {
                throw new IllegalArgumentException("Range, evaluation date and sort are required");
            }
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

    /**
     * One game with its bounded presented releases: of the releases matching the range and active
     * filters, at most one per platform, chosen by the presented-release precedence with the
     * earliest date first. Other matching releases of a platform stay stored and unchanged.
     */
    record Item(
            String gameId,
            String slug,
            String canonicalTitle,
            CatalogueCoverReference cover,
            List<ReleaseRow> releases,
            List<GameDetailsResult.Term> genres) {}

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
            Instant lastVerifiedAt,
            VerificationLevel verificationLevel,
            ReviewStatus reviewStatus,
            ReleaseStage stage) {}
}
