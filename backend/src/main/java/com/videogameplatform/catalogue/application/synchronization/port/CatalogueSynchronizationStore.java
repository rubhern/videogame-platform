package com.videogameplatform.catalogue.application.synchronization.port;

import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
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

    CatalogueContext loadContext();

    Optional<GameState> loadGame(String providerId, int maxReleases);

    WriteResult saveGame(UUID runId, GameWrite write);

    void completeRun(CatalogueSynchronizationReport report, int retainedRuns);

    Optional<CatalogueSynchronizationReport> lastRun();

    record CatalogueContext(
            Map<String, UUID> platformIdsByCode, Map<String, UUID> regionIdsByCode) {}

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

    record PublishedRelease(
            UUID releaseId,
            ReleaseIdentity identity,
            ReleaseDate date,
            ReleaseStatus status,
            Instant lastVerifiedAt,
            VerificationLevel verificationLevel,
            ReviewStatus reviewStatus) {}

    record ReleaseIdentity(UUID gameId, UUID platformId, UUID regionId) {}

    sealed interface CoverSelection {

        String alternativeText();

        record Provider(String reference, String sourceUrl, String alternativeText)
                implements CoverSelection {}

        record ProductFallback(String assetPath, String sourceName, String alternativeText)
                implements CoverSelection {}
    }

    /** Validated state; identity is supplied separately by ReleaseWrite's external reference. */
    record PlannedRelease(
            ReleaseIdentity identity,
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
