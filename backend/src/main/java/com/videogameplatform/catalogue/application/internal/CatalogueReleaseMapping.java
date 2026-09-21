package com.videogameplatform.catalogue.application.internal;

import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.ReleaseRow;
import com.videogameplatform.catalogue.domain.EffectiveReleaseStatusPolicy;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.SourceKind;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import java.time.Instant;
import java.time.LocalDate;

/** Shared full release evidence mapping for public catalogue reads. */
public final class CatalogueReleaseMapping {
    private CatalogueReleaseMapping() {}

    public static BrowseReleasesResult.Release map(
            ReleaseRow row,
            Instant evaluatedAt,
            LocalDate evaluatedOn,
            CatalogueFreshnessPolicy freshnessPolicy) {
        ReleaseStatus effectiveStatus =
                EffectiveReleaseStatusPolicy.effectiveStatus(
                        row.status(), row.releaseDate(), evaluatedOn);
        BrowseReleasesResult.Taxonomy platform =
                new BrowseReleasesResult.Taxonomy(row.platform().id(), row.platform().name());
        BrowseReleasesResult.Taxonomy region =
                new BrowseReleasesResult.Taxonomy(row.region().id(), row.region().name());
        return new BrowseReleasesResult.Release(
                row.releaseId(),
                row.gameId(),
                platform,
                region,
                CatalogueReadMapping.toReleaseDate(row.releaseDate()),
                CatalogueReadMapping.toStatus(effectiveStatus),
                new BrowseReleasesResult.Provenance(
                        toSource(row.sourceKind()), row.sourceName(), row.sourceEntityType()),
                row.providerUpdatedAt(),
                row.lastSyncedAt(),
                row.lastVerifiedAt(),
                toVerification(row.verificationLevel()),
                toReview(row.reviewStatus()),
                CatalogueReadMapping.toFreshness(
                        freshnessPolicy.status(row.lastSyncedAt(), evaluatedAt)));
    }

    private static BrowseReleasesResult.Source toSource(SourceKind source) {
        return switch (source) {
            case EXTERNAL_PROVIDER -> BrowseReleasesResult.Source.EXTERNAL_PROVIDER;
            case PRODUCT_CURATED -> BrowseReleasesResult.Source.PRODUCT_CURATED;
            case OFFICIAL_SOURCE -> BrowseReleasesResult.Source.OFFICIAL_SOURCE;
        };
    }

    private static BrowseReleasesResult.Verification toVerification(VerificationLevel source) {
        return switch (source) {
            case PROVIDER_ONLY -> BrowseReleasesResult.Verification.PROVIDER_ONLY;
            case VERIFIED -> BrowseReleasesResult.Verification.VERIFIED;
        };
    }

    private static BrowseReleasesResult.Review toReview(ReviewStatus source) {
        return switch (source) {
            case NOT_REQUIRED -> BrowseReleasesResult.Review.NOT_REQUIRED;
            case REQUIRED -> BrowseReleasesResult.Review.REQUIRED;
        };
    }
}
