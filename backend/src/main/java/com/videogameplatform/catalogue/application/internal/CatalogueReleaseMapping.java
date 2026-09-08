package com.videogameplatform.catalogue.application.internal;

import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.Item;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.SourceKind;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import java.time.Instant;

/** Shared full release evidence mapping for public catalogue reads. */
public final class CatalogueReleaseMapping {
    private CatalogueReleaseMapping() {}

    public static BrowseReleasesResult.Release map(
            Item item, Instant evaluatedAt, CatalogueFreshnessPolicy freshnessPolicy) {
        BrowseReleasesResult.Taxonomy platform =
                new BrowseReleasesResult.Taxonomy(item.platform().id(), item.platform().name());
        BrowseReleasesResult.Taxonomy region =
                new BrowseReleasesResult.Taxonomy(item.region().id(), item.region().name());
        return new BrowseReleasesResult.Release(
                item.releaseId(),
                item.gameId(),
                platform,
                region,
                CatalogueReadMapping.toReleaseDate(item.releaseDate()),
                CatalogueReadMapping.toStatus(item.status()),
                new BrowseReleasesResult.Provenance(
                        toSource(item.sourceKind()), item.sourceName(), item.sourceEntityType()),
                item.providerUpdatedAt(),
                item.lastSyncedAt(),
                item.lastVerifiedAt(),
                toVerification(item.verificationLevel()),
                toReview(item.reviewStatus()),
                CatalogueReadMapping.toFreshness(
                        freshnessPolicy.status(item.lastSyncedAt(), evaluatedAt)));
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
