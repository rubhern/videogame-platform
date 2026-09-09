package com.videogameplatform.catalogue.application.synchronization.internal;

import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.PlannedRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.PublishedRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.ReleaseIdentity;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.SourceKind;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Reconciles one Release matched by external reference, never by mutable tuple or order. */
public final class ReleaseReconciliationPolicy {
    private ReleaseReconciliationPolicy() {}

    public static Optional<PlannedRelease> reconcile(
            UUID gameId,
            ProviderRelease providerRelease,
            PublishedRelease previous,
            Map<String, UUID> platforms,
            Map<String, UUID> regions,
            LocalDate today,
            Instant synchronizedAt,
            Instant updatedAt,
            String source) {
        UUID platform = platforms.get(providerRelease.platformCode());
        UUID region = regions.get(providerRelease.regionCode());
        if (platform == null || region == null) {
            throw new IllegalArgumentException("Unsupported release mapping");
        }
        ReleaseIdentity identity = new ReleaseIdentity(gameId, platform, region);
        ReleaseStatus status =
                ReleaseStatusPolicy.derive(providerRelease.signal(), providerRelease.date(), today);
        boolean unchanged =
                previous != null
                        && identity.equals(previous.identity())
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
                        identity,
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
