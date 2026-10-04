package com.videogameplatform.catalogue.application.synchronization.port;

import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderPlatform;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRegion;
import com.videogameplatform.catalogue.domain.FeaturedMediaPolicy;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStage;
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

    /** Exercise the identical transaction and constraints, rolling everything back. */
    WriteResult previewGame(GameWrite write);

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
            Instant synchronizedAt,
            java.util.Set<String> returnedReleaseReferences,
            int maxReleases,
            FeaturedEvidenceWrite featuredEvidence,
            FeaturedMediaWrite media) {
        public GameWrite {
            releases = List.copyOf(releases);
            returnedReleaseReferences = java.util.Set.copyOf(returnedReleaseReferences);
            java.util.Objects.requireNonNull(featuredEvidence, "featuredEvidence");
            java.util.Objects.requireNonNull(media, "media");
            if (maxReleases < 1 || returnedReleaseReferences.size() > maxReleases) {
                throw new IllegalArgumentException(
                        "A Game write requires a complete bounded reference set");
            }
            for (ReleaseWrite r : releases) {
                if (!returnedReleaseReferences.contains(r.providerId())) {
                    throw new IllegalArgumentException(
                            "Written release must be present in the complete set");
                }
            }
        }
    }

    record ReleaseWrite(String providerId, PlannedRelease release) {}

    /** Complete normalized featured evidence, or last-valid-state preservation on mapping failure. */
    sealed interface FeaturedEvidenceWrite {
        FeaturedEvidenceWrite KEEP = new Keep();

        record Observe(
                Optional<java.time.LocalDate> firstReleaseDate,
                boolean eligibleProduct,
                Optional<Long> hypes)
                implements FeaturedEvidenceWrite {
            public Observe {
                java.util.Objects.requireNonNull(firstReleaseDate, "firstReleaseDate");
                java.util.Objects.requireNonNull(hypes, "hypes");
                if (hypes.isPresent() && hypes.orElseThrow() <= 0) {
                    throw new IllegalArgumentException("An attention count must be positive");
                }
            }
        }

        record Keep() implements FeaturedEvidenceWrite {}
    }

    /**
     * The hero image, card image and optional secondary-card logo a Game write records. Each is either a newer valid
     * selection, which replaces the stored one, or kept: missing, invalid or unavailable provider
     * media never degrade the last valid selection.
     */
    record FeaturedMediaWrite(MediaWrite image, MediaWrite cardImage, MediaWrite logo) {

        public static final FeaturedMediaWrite KEEP =
                new FeaturedMediaWrite(MediaWrite.KEEP, MediaWrite.KEEP, MediaWrite.KEEP);

        public FeaturedMediaWrite {
            java.util.Objects.requireNonNull(image, "image");
            java.util.Objects.requireNonNull(cardImage, "cardImage");
            if (cardImage instanceof MediaWrite.Observe observed
                    && observed.kind() == FeaturedMediaPolicy.ImageKind.LOGO) {
                throw new IllegalArgumentException("A card image cannot be a logo");
            }
            java.util.Objects.requireNonNull(logo, "logo");
            if (image instanceof MediaWrite.Observe observed
                    && observed.kind() == FeaturedMediaPolicy.ImageKind.LOGO) {
                throw new IllegalArgumentException("A featured image cannot be a logo");
            }
            if (logo instanceof MediaWrite.Observe observed
                    && observed.kind() != FeaturedMediaPolicy.ImageKind.LOGO) {
                throw new IllegalArgumentException("A title logo must be a logo");
            }
        }
    }

    sealed interface MediaWrite {

        MediaWrite KEEP = new Keep();

        /** A selected provider image with its attribution page, observed at the write's time. */
        record Observe(
                FeaturedMediaPolicy.ImageKind kind,
                String reference,
                int width,
                int height,
                boolean transparent,
                String sourceUrl)
                implements MediaWrite {
            public Observe {
                java.util.Objects.requireNonNull(kind, "kind");
                if (reference == null || reference.isBlank()) {
                    throw new IllegalArgumentException("An observed image requires a reference");
                }
                if (width <= 0 || height <= 0) {
                    throw new IllegalArgumentException("An observed image requires its dimensions");
                }
                if (sourceUrl == null || sourceUrl.isBlank()) {
                    throw new IllegalArgumentException("An observed image requires attribution");
                }
            }
        }

        record Keep() implements MediaWrite {}
    }

    record WriteResult(
            boolean createdGame,
            boolean updatedGame,
            int createdReleases,
            int updatedReleases,
            int unchangedReleases,
            int deletedReleases) {}

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
            ReviewStatus reviewStatus,
            ReleaseStage stage) {}

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
            ReviewStatus reviewStatus,
            ReleaseStage stage) {}
}
