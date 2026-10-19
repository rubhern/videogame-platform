package com.videogameplatform.catalogue.application.synchronization.port;

import com.videogameplatform.catalogue.application.acquisition.SynchronizationCoverage;
import com.videogameplatform.catalogue.application.acquisition.SynchronizationStatus;
import com.videogameplatform.catalogue.application.administration.AbandonmentOutcome;
import com.videogameplatform.catalogue.application.administration.StopRequestOutcome;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderGameDetails;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderPlatform;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRegion;
import com.videogameplatform.catalogue.domain.ExternalScore;
import com.videogameplatform.catalogue.domain.FeaturedMediaPolicy;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStage;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.SourceKind;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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

    /**
     * A worker's safe point: proves the run is alive with a heartbeat and answers whether an
     * operator asked it to stop, in one short statement that holds no lock beyond it.
     *
     * @throws SynchronizationWriteException with {@code OWNERSHIP_LOST} when the run no longer
     *     holds ownership, for example because an operator or a later run start abandoned it
     */
    RunControl checkpoint(UUID runId);

    /** What a worker does at a safe point. */
    enum RunControl {
        CONTINUE,
        STOP_REQUESTED
    }

    /**
     * Records the first stop request of the provider's run in flight with this identifier; a
     * finished or unknown run is answered without any change.
     */
    StopRequestOutcome requestStop(UUID runId, Instant requestedAt);

    /**
     * Abandons the provider's run in flight with this identifier only while its last heartbeat is
     * before {@code staleBefore}, deciding both in one statement that waits for, and then sees, a
     * concurrent heartbeat of its worker. The abandoned run is recorded with {@link
     * AbandonmentOutcome#ABANDONED_BY_OPERATOR} at {@code abandonedAt}, without counters.
     */
    AbandonmentOutcome abandonRun(UUID runId, Instant abandonedAt, Instant staleBefore);

    Optional<GameState> loadGame(String providerId, int maxReleases);

    WriteResult saveGame(UUID runId, GameWrite write);

    /** Exercise the identical transaction and constraints, rolling everything back. */
    WriteResult previewGame(GameWrite write);

    void completeRun(CatalogueSynchronizationReport report, int retainedRuns);

    /**
     * Completes a run that read every provider page of its requested interval, with at least one
     * Game succeeded, and in the same fenced transaction records that interval as covered by it,
     * together with its failed Game count. Coverage is never pruned with runs.
     */
    void completeCoveredRun(CatalogueSynchronizationReport report, int retainedRuns);

    Optional<CatalogueSynchronizationReport> lastRun();

    /** At most {@code limit} stored covered intervals overlapping the inclusive range, by start. */
    List<CoveredInterval> coveredIntervals(LocalDate from, LocalDate to, int limit);

    /** One stored disjoint interval and the latest complete scan that covered it. */
    record CoveredInterval(
            LocalDate from, LocalDate to, SynchronizationCoverage.LastScan lastScan) {}

    /**
     * The run holding run ownership, if any, first, then at most {@code limit} finished retained
     * runs, newest first; one statement reads them, so a run finishing meanwhile appears once.
     */
    List<RetainedRun> retainedRuns(int limit);

    /**
     * A retained run row. An empty {@code result} is a run still holding ownership, without
     * completion, outcome or counters; counters are also absent when no report was recorded.
     * {@code stopRequestedAt} is present once an operator requested the run's stop.
     */
    record RetainedRun(
            UUID runId,
            LocalDate from,
            LocalDate to,
            Instant startedAt,
            Instant heartbeatAt,
            Optional<SynchronizationStatus.Result> result,
            Optional<Instant> completedAt,
            Optional<String> outcomeCode,
            Optional<CatalogueSynchronizationReport.Counters> counters,
            Optional<Instant> stopRequestedAt) {}

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
            FeaturedMediaWrite media,
            DetailsWrite details,
            ExternalScoreWrite playerScore) {

        /** A write that keeps the Game's last valid player score. */
        public GameWrite(
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
                FeaturedMediaWrite media,
                DetailsWrite details) {
            this(
                    providerId,
                    gameId,
                    creating,
                    title,
                    slug,
                    cover,
                    releases,
                    synchronizedAt,
                    returnedReleaseReferences,
                    maxReleases,
                    featuredEvidence,
                    media,
                    details,
                    ExternalScoreWrite.KEEP);
        }

        /** A write that keeps the Game's last valid details. */
        public GameWrite(
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
            this(
                    providerId,
                    gameId,
                    creating,
                    title,
                    slug,
                    cover,
                    releases,
                    synchronizedAt,
                    returnedReleaseReferences,
                    maxReleases,
                    featuredEvidence,
                    media,
                    DetailsWrite.KEEP);
        }

        public GameWrite {
            java.util.Objects.requireNonNull(details, "details");
            java.util.Objects.requireNonNull(playerScore, "playerScore");
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

    /**
     * The Game's complete detail metadata from one valid provider answer, or last-valid-state
     * preservation when the answer was invalid. An observed answer replaces the provider-owned
     * summary and every developer, publisher, genre and game-mode link, including clearing what the
     * provider no longer states; product-owned editorial or other-source summaries are never
     * replaced. Companies, genres and game modes are typed provider references the store resolves to
     * product identity, reusing a known reference or creating the product entity in this write.
     */
    sealed interface DetailsWrite {
        DetailsWrite KEEP = new Keep();

        record Observe(ProviderGameDetails details) implements DetailsWrite {
            public Observe {
                java.util.Objects.requireNonNull(details, "details");
            }
        }

        record Keep() implements DetailsWrite {}
    }

    /**
     * The Game's external player score from one valid provider answer, or last-valid-state
     * preservation. An observed answer records the score, or that the source currently states none,
     * as the Game's successful check at the write's time; the stored score changes only when its
     * value, count or presence does. A kept score is not touched, so its last check never advances.
     * A score is attributed to the provider page of its own work; the Game-record update time is
     * the provider's claim about its record, never the score's own update time.
     */
    sealed interface ExternalScoreWrite {
        ExternalScoreWrite KEEP = new Keep();

        record Observe(
                Optional<ExternalScore> score,
                Optional<String> sourceUrl,
                Optional<Instant> providerRecordUpdatedAt)
                implements ExternalScoreWrite {
            public Observe {
                java.util.Objects.requireNonNull(score, "score");
                java.util.Objects.requireNonNull(sourceUrl, "sourceUrl");
                java.util.Objects.requireNonNull(
                        providerRecordUpdatedAt, "providerRecordUpdatedAt");
                if (sourceUrl.isPresent() && sourceUrl.orElseThrow().isBlank()) {
                    throw new IllegalArgumentException("An attribution page cannot be blank");
                }
                if (score.isPresent() && sourceUrl.isEmpty()) {
                    throw new IllegalArgumentException(
                            "An external score requires attribution to its source work");
                }
            }
        }

        record Keep() implements ExternalScoreWrite {}
    }

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
            int deletedReleases,
            ExternalScoreChange playerScoreChange) {

        /** A write that changed no stored player score. */
        public WriteResult(
                boolean createdGame,
                boolean updatedGame,
                int createdReleases,
                int updatedReleases,
                int unchangedReleases,
                int deletedReleases) {
            this(
                    createdGame,
                    updatedGame,
                    createdReleases,
                    updatedReleases,
                    unchangedReleases,
                    deletedReleases,
                    ExternalScoreChange.NONE);
        }

        public WriteResult {
            java.util.Objects.requireNonNull(playerScoreChange, "playerScoreChange");
        }
    }

    /**
     * What one Game write did to its stored usable player score, judged by the same statement that
     * wrote it. {@code NONE} covers every write that neither held nor now holds a usable score, a
     * kept score, and an answer older than the stored check, which writes nothing.
     */
    enum ExternalScoreChange {
        /** A usable score is stored where none was. */
        CREATED,
        /** A usable score replaced a different usable value or count. */
        UPDATED,
        /** A valid answer stating no score cleared a usable one. */
        REMOVED,
        /** A usable score was re-checked identical. */
        UNCHANGED,
        NONE
    }

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
