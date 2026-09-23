package com.videogameplatform.catalogue.application.synchronization.internal;

import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRegion;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.PlannedRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.PublishedRelease;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.SourceKind;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Reconciles one Release matched by external reference, never by mutable tuple or order. */
public final class ReleaseReconciliationPolicy {
    private ReleaseReconciliationPolicy() {}

    public static Optional<PlannedRelease> reconcile(
            ProviderRelease providerRelease,
            PublishedRelease previous,
            Instant synchronizedAt,
            Instant updatedAt,
            String source) {
        ReleaseStatus status = ReleaseStatusPolicy.persistedStatus(providerRelease.signal());
        // Platform and region are compared by their stable provider reference, not by product UUID:
        // a mutable provider slug or name change never counts as a taxonomy change, and the store
        // resolves both references to the same product identity across runs.
        String platformProviderId = providerRelease.platform().providerId();
        String regionProviderId =
                providerRelease.region().map(ProviderRegion::providerId).orElse(null);
        boolean unchanged =
                previous != null
                        && platformProviderId.equals(previous.platformProviderId())
                        && Objects.equals(regionProviderId, previous.regionProviderId())
                        && providerRelease.date().equals(previous.date())
                        && status == previous.status();
        if (previous != null
                && !unchanged
                && previous.verificationLevel() == VerificationLevel.VERIFIED) {
            // REL-008/REL-010: provider evidence cannot overwrite previously verified evidence.
            return Optional.empty();
        }
        return Optional.of(
                new PlannedRelease(
                        providerRelease.platform(),
                        providerRelease.region(),
                        providerRelease.date(),
                        status,
                        SourceKind.EXTERNAL_PROVIDER,
                        source,
                        "release_date",
                        updatedAt,
                        synchronizedAt,
                        unchanged ? previous.lastVerifiedAt() : null,
                        unchanged ? previous.verificationLevel() : VerificationLevel.PROVIDER_ONLY,
                        unchanged
                                ? previous.reviewStatus()
                                : previous != null
                                                || providerRelease.date()
                                                        instanceof ReleaseDate.Unknown
                                        ? ReviewStatus.REQUIRED
                                        : ReviewStatus.NOT_REQUIRED));
    }
}
