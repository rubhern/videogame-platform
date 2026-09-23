package com.videogameplatform.catalogue.application.synchronization.port;

import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderPlatform;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRegion;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.SourceKind;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL boundary: fenced operational runs and one atomic Game write. */
public interface CatalogueSynchronizationStore {
    Optional<UUID> beginRun(
            String provider,
            CatalogueSynchronizationRequest request,
            Instant startedAt,
            Duration abandonAfter);

    void heartbeat(UUID runId);

    Optional<GameState> loadGame(String providerId, int maxReleases);

    WriteResult saveGame(UUID runId, GameWrite write);

    void completeRun(CatalogueSynchronizationReport report, int retainedRuns);

    Optional<CatalogueSynchronizationReport> lastRun();

    record GameState(
            UUID gameId,
            String title,
            String slug,
            CoverSelection cover,
            Map<String, PublishedRelease> releases) {}

    record GameWrite(
            String providerId,
            UUID gameId,
            boolean creating,
            String title,
            String slug,
            CoverSelection cover,
            List<ReleaseWrite> releases,
            Instant synchronizedAt) {}

    record ReleaseWrite(String providerId, PlannedRelease release) {}

    record WriteResult(
            boolean createdGame,
            boolean updatedGame,
            int createdReleases,
            int updatedReleases,
            int unchangedReleases) {}

    /**
     * A previously published release, matched by its provider release reference. Platform and region
     * are exposed as their provider references so reconciliation compares stable provider identity;
     * {@code regionProviderId} is null for the product 'unknown' sentinel, and either reference is
     * null for a legacy taxonomy row that predates provider references.
     */
    record PublishedRelease(
            UUID releaseId,
            UUID gameId,
            String platformProviderId,
            String regionProviderId,
            ReleaseDate date,
            ReleaseStatus status,
            Instant lastVerifiedAt,
            VerificationLevel verificationLevel,
            ReviewStatus reviewStatus) {}

    sealed interface CoverSelection {

        String alternativeText();

        record Provider(String reference, String sourceUrl, String alternativeText)
                implements CoverSelection {}

        record ProductFallback(String assetPath, String sourceName, String alternativeText)
                implements CoverSelection {}
    }

    /**
     * Validated release state. The release identity is supplied by ReleaseWrite's external
     * reference; the game identity by GameWrite. Platform and region are typed provider references
     * the store resolves to product taxonomy, reusing a known reference or creating the product
     * entity as part of this write. An absent region resolves to the product 'unknown' sentinel.
     */
    record PlannedRelease(
            ProviderPlatform platform,
            Optional<ProviderRegion> region,
            ReleaseDate date,
            ReleaseStatus status,
            SourceKind sourceKind,
            String sourceName,
            String sourceEntityType,
            Instant providerUpdatedAt,
            Instant lastSynchronizedAt,
            Instant lastVerifiedAt,
            VerificationLevel verificationLevel,
            ReviewStatus reviewStatus) {}
}
