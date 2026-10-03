package com.videogameplatform.catalogue.application.synchronization.internal;

import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRegion;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.PlannedRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.PublishedRelease;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStage;
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
        boolean taxonomyConflict =
                previous != null
                        && (!platformProviderId.equals(previous.platformProviderId())
                                || !Objects.equals(regionProviderId, previous.regionProviderId()));
        boolean unchanged =
                previous != null
                        && !taxonomyConflict
                        && providerRelease.date().equals(previous.date())
                        && (status == previous.status()
                                // #177 left legacy known-date occurrences persisted as released.
                                // Normalizing that derived fact is not a provider evidence change.
                                // Unknown-date released evidence remains explicit and independent.
                                || status == ReleaseStatus.ANNOUNCED
                                        && previous.status() == ReleaseStatus.RELEASED
                                        && !(previous.date() instanceof ReleaseDate.Unknown));
        // Missing/unsupported evidence cannot erase an established stage. Enriching an
        // unspecified stage preserves accepted date verification; a known conflict does not.
        ReleaseStage stage =
                providerRelease.stage() == ReleaseStage.UNKNOWN && previous != null
                        ? previous.stage()
                        : providerRelease.stage();
        boolean stageConflict =
                previous != null
                        && previous.stage() != ReleaseStage.UNKNOWN
                        && stage != previous.stage();
        if (previous != null
                && (!unchanged || stageConflict)
                && previous.verificationLevel() == VerificationLevel.VERIFIED) {
            // REL-008/REL-010: provider evidence cannot overwrite previously verified evidence.
            return Optional.empty();
        }
        // Provider-only review represents current uncertainty/conflict, never a history of change.
        // A coherent date refinement or explicit lifecycle signal is not ambiguity. Verified
        // evidence retains its accepted review state; conflicts above still withhold the write.
        ReviewStatus review =
                previous != null && previous.verificationLevel() == VerificationLevel.VERIFIED
                        ? previous.reviewStatus()
                        : providerRelease.date() instanceof ReleaseDate.Unknown
                                        || taxonomyConflict
                                        || stageConflict
                                ? ReviewStatus.REQUIRED
                                : ReviewStatus.NOT_REQUIRED;
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
                        unchanged && !stageConflict ? previous.lastVerifiedAt() : null,
                        unchanged && !stageConflict
                                ? previous.verificationLevel()
                                : VerificationLevel.PROVIDER_ONLY,
                        review,
                        stage));
    }
}
