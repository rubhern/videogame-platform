package com.videogameplatform.catalogue.application.synchronization.internal;

import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport.Counters;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.SynchronizeCatalogueUseCase;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.LogoBatch;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderImage;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderWork;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderWorkBatch;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ReleasePage;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.CoverSelection;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.FeaturedEvidenceWrite;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.FeaturedMediaWrite;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.GameState;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.GameWrite;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.MediaWrite;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.PlannedRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.PublishedRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.ReleaseWrite;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.WriteResult;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderCallStatistics;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderMappingFailure;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderRequestException;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress.Failure;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress.GameResult;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress.Run;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress.Stage;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationWriteException;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizedGameIdentity;
import com.videogameplatform.catalogue.domain.CatalogueSlug;
import com.videogameplatform.catalogue.domain.FeaturedMediaPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Synchronizes the complete requested date interval with bounded, in-call provider paging.
 *
 * <p>Progress events report where the run is and why a Game failed; they never change counters,
 * outcomes or the durable report.
 */
public final class CatalogueSynchronizationService implements SynchronizeCatalogueUseCase {
    static final String WORK_NOT_RETURNED = "WORK_NOT_RETURNED";
    static final String TITLE_MISSING = "TITLE_MISSING";
    static final String RELEASE_LIMIT_EXCEEDED = "RELEASE_LIMIT_EXCEEDED";
    static final String DUPLICATE_RELEASE_REFERENCE = "DUPLICATE_RELEASE_REFERENCE";
    static final String RECORD_REJECTED = "RECORD_REJECTED";
    static final String UNEXPECTED_FAILURE = "UNEXPECTED_FAILURE";

    private static final Set<ProviderMappingFailure> BLOCKING_MAPPING_FAILURES =
            Set.of(
                    ProviderMappingFailure.RELEASE_DATE_INVALID,
                    ProviderMappingFailure.RECORD_UNREADABLE,
                    ProviderMappingFailure.DUPLICATE_RELEASE_TUPLE);

    private final CatalogueSynchronizationStore store;
    private final CatalogueProviderPort provider;
    private final Clock clock;
    private final SynchronizationPolicy policy;
    private final CoverSelectionPolicy covers;
    private final SynchronizationProgress progress;

    public CatalogueSynchronizationService(
            CatalogueSynchronizationStore store,
            CatalogueProviderPort provider,
            Clock clock,
            SynchronizationPolicy policy,
            CoverSelectionPolicy covers,
            SynchronizationProgress progress) {
        this.store = store;
        this.provider = provider;
        this.clock = clock;
        this.policy = policy;
        this.covers = covers;
        this.progress = progress;
    }

    @Override
    public CatalogueSynchronizationReport synchronize(CatalogueSynchronizationRequest request) {
        Instant started = clock.instant();
        Counts counts = new Counts();
        if (!provider.isConfigured()) {
            return skipped(request, started, "SYNCHRONIZATION_DISABLED", counts);
        }
        Optional<UUID> acquired =
                store.beginRun(provider.providerName(), request, started, policy.abandonRunAfter());
        if (acquired.isEmpty()) {
            return skipped(request, started, "SYNCHRONIZATION_ALREADY_RUNNING", counts);
        }
        UUID runId = acquired.orElseThrow();
        Run run = progress.started(runId, request, started, policy.providerPageSize());
        long afterGameId = 0;
        int pageNumber = 0;
        try {
            while (true) {
                pageNumber++;
                run.pageRequested(pageNumber, counters(counts));
                final ReleasePage page;
                try {
                    page =
                            provider.releaseGames(
                                    request.from(),
                                    request.to(),
                                    afterGameId,
                                    policy.providerPageSize());
                    counts.statistics = counts.statistics.plus(page.statistics());
                    counts.inspected += page.inspected();
                } catch (ProviderRequestException failure) {
                    counts.statistics = counts.statistics.plus(failure.statistics());
                    counts.failed++;
                    run.runFailed(
                            Failure.of(Stage.PROVIDER_PAGE, failure.code().name()),
                            counters(counts));
                    break;
                }
                run.pageFetched(pageNumber, page.gameIds().size(), counters(counts));
                PageEvidence evidence =
                        new PageEvidence(lookUpLogos(pageNumber, page.gameIds(), run, counts));
                int position = 0;
                for (String providerId : page.gameIds()) {
                    position++;
                    synchronizeGame(
                            runId,
                            new GameProgress(run, pageNumber, position),
                            providerId,
                            started,
                            counts,
                            false,
                            evidence);
                }
                store.heartbeat(runId);
                run.pageCompleted(pageNumber, counters(counts));
                afterGameId = page.nextGameId();
                if (page.completed()) {
                    break;
                }
            }
            SynchronizationOutcome outcome = outcome(counts);
            CatalogueSynchronizationReport result =
                    report(
                            runId,
                            request,
                            started,
                            outcome,
                            outcome == SynchronizationOutcome.SUCCEEDED
                                    ? "SYNCHRONIZATION_COMPLETED"
                                    : outcome == SynchronizationOutcome.PARTIAL
                                            ? "SYNCHRONIZATION_COMPLETED_WITH_FAILURES"
                                            : "SYNCHRONIZATION_FAILED",
                            counts);
            store.completeRun(result, policy.retainedRuns());
            run.finished(result);
            return result;
        } catch (RuntimeException failure) {
            counts.failed++;
            run.runFailed(runFailure(failure), counters(counts));
            CatalogueSynchronizationReport result =
                    report(
                            runId,
                            request,
                            started,
                            successfulGames(counts) > 0
                                    ? SynchronizationOutcome.PARTIAL
                                    : SynchronizationOutcome.FAILED,
                            "SYNCHRONIZATION_FAILED",
                            counts);
            store.completeRun(result, policy.retainedRuns());
            run.finished(result);
            if (!(failure instanceof ProviderRequestException
                    || failure instanceof SynchronizationWriteException)) {
                throw failure;
            }
            return result;
        }
    }

    @Override
    public CatalogueSynchronizationReport repairGames(List<String> providerIds, boolean dryRun) {
        if (providerIds.size() > 100
                || providerIds.stream().distinct().count() != providerIds.size()) {
            throw new IllegalArgumentException(
                    "Repair requires at most 100 unique Game references");
        }
        Instant started = clock.instant();
        var day = started.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        var request = new CatalogueSynchronizationRequest(day, day);
        Counts counts = new Counts();
        if (!provider.isConfigured()) {
            return skipped(request, started, "SYNCHRONIZATION_DISABLED", counts);
        }
        UUID runId = null;
        if (!dryRun) {
            var acquired =
                    store.beginRun(
                            provider.providerName(), request, started, policy.abandonRunAfter());
            if (acquired.isEmpty()) {
                return skipped(request, started, "SYNCHRONIZATION_ALREADY_RUNNING", counts);
            }
            runId = acquired.orElseThrow();
        }
        Run run =
                dryRun
                        ? SynchronizationProgress.NONE.started(
                                null, request, started, providerIds.size())
                        : progress.started(runId, request, started, providerIds.size());
        try {
            int position = 0;
            for (String id : providerIds) {
                // Repair never discovers/imports an unknown Game reference.
                try {
                    if (store.loadGame(id, policy.maxReleasesPerGame()).isEmpty()) {
                        counts.failed++;
                        continue;
                    }
                } catch (SynchronizationWriteException failure) {
                    fail(
                            new GameProgress(run, 1, ++position),
                            Failure.of(Stage.PERSISTENCE, failure.reason().name()),
                            failure.game(),
                            counts);
                    continue;
                }
                // Repair has no logo lookup; featured evidence and images arrive with the work.
                synchronizeGame(
                        runId,
                        new GameProgress(run, 1, ++position),
                        id,
                        started,
                        counts,
                        dryRun,
                        PageEvidence.NOT_REQUESTED);
            }
        } catch (RuntimeException failure) {
            counts.failed++;
            run.runFailed(runFailure(failure), counters(counts));
        }
        var result =
                report(
                        runId,
                        request,
                        started,
                        outcome(counts),
                        dryRun ? "CATALOGUE_REPAIR_PREVIEW" : "CATALOGUE_REPAIR_COMPLETED",
                        counts);
        if (!dryRun) {
            store.completeRun(result, policy.retainedRuns());
            run.finished(result);
        }
        return result;
    }

    /**
     * Reads the logos of one provider page's Games with bounded requests. A failure never fails a
     * Game: every Game of the page keeps its last valid logo and the run goes on.
     */
    private LogoLookup lookUpLogos(int page, List<String> providerIds, Run run, Counts counts) {
        if (providerIds.isEmpty()) {
            return LogoLookup.complete(Map.of());
        }
        return lookUp(
                        page,
                        providerIds,
                        run,
                        counts,
                        Stage.PROVIDER_LOGOS,
                        () -> {
                            LogoBatch batch = provider.logos(providerIds);
                            return new Answer<>(
                                    LogoLookup.complete(batch.logos()), batch.statistics());
                        })
                .orElse(LogoLookup.UNAVAILABLE);
    }

    /** Runs one page-level lookup, accounting its provider calls whether or not it succeeds. */
    private <T> Optional<T> lookUp(
            int page,
            List<String> providerIds,
            Run run,
            Counts counts,
            Stage stage,
            Supplier<Answer<T>> request) {
        try {
            Answer<T> answer = request.get();
            counts.statistics = counts.statistics.plus(answer.statistics());
            return Optional.of(answer.value());
        } catch (ProviderRequestException failure) {
            counts.statistics = counts.statistics.plus(failure.statistics());
            run.pageLookupUnavailable(
                    page,
                    providerIds.size(),
                    Failure.of(stage, failure.code().name()),
                    counters(counts));
            return Optional.empty();
        }
    }

    private record Answer<T>(T value, ProviderCallStatistics statistics) {}

    private void synchronizeGame(
            UUID runId,
            GameProgress game,
            String providerId,
            Instant started,
            Counts counts,
            boolean dryRun,
            PageEvidence evidence) {
        try {
            ProviderWorkBatch workBatch = provider.fetchWorks(List.of(providerId));
            counts.statistics = counts.statistics.plus(workBatch.statistics());
            if (workBatch.works().size() != 1
                    || !providerId.equals(workBatch.works().getFirst().providerId())) {
                fail(
                        game,
                        Failure.of(Stage.PROVIDER_GAME, WORK_NOT_RETURNED),
                        Optional.empty(),
                        counts);
                return;
            }
            reconcile(runId, game, workBatch.works().getFirst(), started, counts, dryRun, evidence);
        } catch (ProviderRequestException failure) {
            counts.statistics = counts.statistics.plus(failure.statistics());
            fail(
                    game,
                    Failure.of(Stage.PROVIDER_GAME, failure.code().name()),
                    Optional.empty(),
                    counts);
        } catch (SynchronizationWriteException failure) {
            // Loading the published Game failed; the store names it when it already knew it.
            fail(
                    game,
                    Failure.of(Stage.PERSISTENCE, failure.reason().name()),
                    failure.game(),
                    counts);
        } catch (IllegalArgumentException _) {
            fail(game, Failure.of(Stage.RECONCILIATION, RECORD_REJECTED), Optional.empty(), counts);
        }
    }

    private void fail(
            GameProgress game,
            Failure failure,
            Optional<SynchronizedGameIdentity> identity,
            Counts counts) {
        counts.failed++;
        game.run().gameFailed(game.page(), game.position(), failure, identity, counters(counts));
    }

    private void succeed(GameProgress game, GameResult result, Counts counts) {
        game.run().gameSucceeded(game.page(), game.position(), result, counters(counts));
    }

    private static Failure runFailure(RuntimeException failure) {
        if (failure instanceof SynchronizationWriteException write) {
            return Failure.of(Stage.PERSISTENCE, write.reason().name());
        }
        if (failure instanceof ProviderRequestException providerFailure) {
            return Failure.of(Stage.PROVIDER_PAGE, providerFailure.code().name());
        }
        return new Failure(
                Stage.RUN, UNEXPECTED_FAILURE, Optional.of(failure.getClass().getName()));
    }

    private static Optional<String> validationFailure(ProviderWork work, int maxReleases) {
        if (work.title() == null || work.title().isBlank()) {
            return Optional.of(TITLE_MISSING);
        }
        if (work.releases().size() > maxReleases) {
            return Optional.of(RELEASE_LIMIT_EXCEEDED);
        }
        return work.mappingFailures().stream()
                .filter(BLOCKING_MAPPING_FAILURES::contains)
                .findFirst()
                .map(Enum::name);
    }

    private static SynchronizationOutcome outcome(Counts counts) {
        if (counts.failed == 0) {
            return SynchronizationOutcome.SUCCEEDED;
        }
        return successfulGames(counts) == 0
                ? SynchronizationOutcome.FAILED
                : SynchronizationOutcome.PARTIAL;
    }

    private static long successfulGames(Counts counts) {
        return counts.created + counts.updated + counts.unchanged + counts.deferred;
    }

    private void reconcile(
            UUID runId,
            GameProgress game,
            ProviderWork work,
            Instant synchronizedAt,
            Counts counts,
            boolean dryRun,
            PageEvidence evidence) {
        Optional<GameState> existing =
                store.loadGame(work.providerId(), policy.maxReleasesPerGame());
        Optional<SynchronizedGameIdentity> published =
                existing.map(
                        state -> SynchronizedGameIdentity.published(state.gameId(), state.slug()));
        if (existing.isEmpty() && !GameImportPolicy.evaluate(work).accepted()) {
            counts.deferred++;
            succeed(game, GameResult.DEFERRED, counts);
            return;
        }
        Optional<String> invalid = validationFailure(work, policy.maxReleasesPerGame());
        if (invalid.isPresent()) {
            // A new Game has no identity yet: validation precedes assignment.
            fail(game, Failure.of(Stage.VALIDATION, invalid.orElseThrow()), published, counts);
            return;
        }
        final SynchronizedGameIdentity identity;
        try {
            identity = published.orElseGet(() -> assignIdentity(work));
        } catch (IllegalArgumentException _) {
            // The title yields no navigable slug, so no identity could be assigned.
            fail(game, Failure.of(Stage.RECONCILIATION, RECORD_REJECTED), published, counts);
            return;
        }
        try {
            publish(
                    runId,
                    game,
                    work,
                    existing,
                    identity,
                    synchronizedAt,
                    counts,
                    dryRun,
                    evidence);
        } catch (DuplicateReleaseReference _) {
            fail(
                    game,
                    Failure.of(Stage.RECONCILIATION, DUPLICATE_RELEASE_REFERENCE),
                    Optional.of(identity),
                    counts);
        } catch (SynchronizationWriteException failure) {
            fail(
                    game,
                    Failure.of(Stage.PERSISTENCE, failure.reason().name()),
                    Optional.of(identity),
                    counts);
        } catch (IllegalArgumentException _) {
            fail(
                    game,
                    Failure.of(Stage.RECONCILIATION, RECORD_REJECTED),
                    Optional.of(identity),
                    counts);
        }
    }

    /** A new Game's identity; it becomes published only if its write commits. */
    private static SynchronizedGameIdentity assignIdentity(ProviderWork work) {
        UUID gameId = UUID.randomUUID();
        String slug =
                CatalogueSlug.fromTitle(work.title()).distinguishedBy(gameId.toString()).value();
        return SynchronizedGameIdentity.unpublished(gameId, slug);
    }

    private void publish(
            UUID runId,
            GameProgress game,
            ProviderWork work,
            Optional<GameState> existing,
            SynchronizedGameIdentity identity,
            Instant synchronizedAt,
            Counts counts,
            boolean dryRun,
            PageEvidence evidence) {
        UUID gameId = identity.gameId();
        String slug = identity.slug();
        CoverSelection cover =
                existing.map(
                                published ->
                                        covers.forReconciliation(work.title(), work.cover())
                                                .orElse(published.cover()))
                        .orElseGet(() -> covers.forImport(work.title(), work.cover()));
        List<ReleaseWrite> releases = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int withheld = 0;
        for (ProviderRelease providerRelease : work.releases()) {
            if (!seen.add(providerRelease.providerId())) {
                throw new DuplicateReleaseReference();
            }
            PublishedRelease previous =
                    existing.map(GameState::releases)
                            .map(values -> values.get(providerRelease.providerId()))
                            .orElse(null);
            Optional<PlannedRelease> release =
                    ReleaseReconciliationPolicy.reconcile(
                            providerRelease,
                            previous,
                            synchronizedAt,
                            work.providerUpdatedAt(),
                            provider.providerName());
            release.ifPresent(
                    value -> releases.add(new ReleaseWrite(providerRelease.providerId(), value)));
            if (release.isEmpty()) {
                withheld++;
            }
        }
        GameWrite write =
                new GameWrite(
                        work.providerId(),
                        gameId,
                        existing.isEmpty(),
                        work.title(),
                        slug,
                        cover,
                        releases,
                        synchronizedAt,
                        seen,
                        policy.maxReleasesPerGame(),
                        featuredEvidence(work),
                        new FeaturedMediaWrite(
                                featuredImage(work),
                                cardImage(work),
                                evidence.logos()
                                        .forGame(work.providerId(), work.attributionUrl())));
        WriteResult result = dryRun ? store.previewGame(write) : store.saveGame(runId, write);
        evidence.count(write, counts);
        switch (write.featuredEvidence()) {
            case FeaturedEvidenceWrite.Observe observed -> {
                if (observed.hypes().isPresent()) counts.popularityObserved++;
                else counts.popularityCleared++;
            }
            case FeaturedEvidenceWrite.Keep _ -> counts.popularityUnavailable++;
        }
        GameResult gameResult;
        if (result.createdGame()) {
            counts.created++;
            gameResult = GameResult.CREATED;
        } else if (result.updatedGame()) {
            counts.updated++;
            gameResult = GameResult.UPDATED;
        } else {
            counts.unchanged++;
            gameResult = GameResult.UNCHANGED;
        }
        counts.unchangedReleases += withheld;
        counts.deletedReleases += result.deletedReleases();
        counts.createdReleases += result.createdReleases();
        counts.updatedReleases += result.updatedReleases();
        counts.unchangedReleases += result.unchangedReleases();
        succeed(game, gameResult, counts);
    }

    @Override
    public Optional<CatalogueSynchronizationReport> lastRun() {
        return store.lastRun();
    }

    private CatalogueSynchronizationReport skipped(
            CatalogueSynchronizationRequest request, Instant started, String code, Counts counts) {
        CatalogueSynchronizationReport result =
                report(null, request, started, SynchronizationOutcome.SKIPPED, code, counts);
        progress.skipped(result);
        return result;
    }

    private CatalogueSynchronizationReport report(
            UUID id,
            CatalogueSynchronizationRequest request,
            Instant started,
            SynchronizationOutcome outcome,
            String code,
            Counts c) {
        return new CatalogueSynchronizationReport(
                id,
                request.from(),
                request.to(),
                started,
                clock.instant(),
                outcome,
                code,
                counters(c));
    }

    private static Counters counters(Counts c) {
        return new Counters(
                c.inspected,
                c.created,
                c.updated,
                c.unchanged,
                c.createdReleases,
                c.updatedReleases,
                c.unchangedReleases,
                c.deferred,
                c.failed,
                c.statistics.requests(),
                c.statistics.retries(),
                c.statistics.latencyMillis(),
                c.deletedReleases,
                c.popularityObserved,
                c.popularityCleared,
                c.popularityUnavailable,
                c.featuredImageObserved,
                c.logoObserved,
                c.logoUnavailable);
    }

    /**
     * The work's featured image under the featured-media policy. Without a usable image or an
     * attribution page, the Game keeps its last valid image: provider silence never degrades it.
     */
    private static MediaWrite featuredImage(ProviderWork work) {
        return work.attributionUrl()
                .flatMap(
                        sourceUrl ->
                                FeaturedMediaPolicy.featuredImage(work.images())
                                        .map(image -> observe(image, sourceUrl)))
                .orElse(MediaWrite.KEEP);
    }

    private static MediaWrite cardImage(ProviderWork work) {
        return work.attributionUrl()
                .flatMap(
                        sourceUrl ->
                                FeaturedMediaPolicy.cardImage(work.images())
                                        .map(image -> observe(image, sourceUrl)))
                .orElse(MediaWrite.KEEP);
    }

    private static MediaWrite observe(ProviderImage image, String sourceUrl) {
        return new MediaWrite.Observe(
                image.kind(),
                image.reference(),
                image.width(),
                image.height(),
                image.transparent(),
                sourceUrl);
    }

    /** Where one Game sits in the run, for progress events only. */
    private record GameProgress(Run run, int page, int position) {}

    /** The page-level lookups one Game write uses. */
    private record PageEvidence(LogoLookup logos) {

        static final PageEvidence NOT_REQUESTED = new PageEvidence(LogoLookup.NOT_REQUESTED);

        /** Counts a committed Game by what happened to its signal, featured image and logo. */
        void count(GameWrite write, Counts counts) {
            if (write.media().image() instanceof MediaWrite.Observe) {
                counts.featuredImageObserved++;
            }
            logos.count(write.media().logo(), counts);
        }
    }

    private static FeaturedEvidenceWrite featuredEvidence(ProviderWork work) {
        return work.featuredEvidence()
                .<FeaturedEvidenceWrite>map(
                        evidence ->
                                new FeaturedEvidenceWrite.Observe(
                                        evidence.firstReleaseDate(),
                                        GameImportPolicy.importableTypes().contains(work.type())
                                                && !evidence.edition(),
                                        evidence.hypes()))
                .orElse(FeaturedEvidenceWrite.KEEP);
    }

    /**
     * One provider page's logo answer. A complete answer offers each Game's logos to the
     * featured-media policy; a Game without a suitable one, an unavailable answer, or an operation
     * that never asked, keeps its last valid logo.
     */
    private record LogoLookup(
            Map<String, List<ProviderImage>> logos, boolean complete, boolean requested) {

        static final LogoLookup UNAVAILABLE = new LogoLookup(Map.of(), false, true);
        static final LogoLookup NOT_REQUESTED = new LogoLookup(Map.of(), false, false);

        static LogoLookup complete(Map<String, List<ProviderImage>> logos) {
            return new LogoLookup(Map.copyOf(logos), true, true);
        }

        MediaWrite forGame(String providerId, Optional<String> attributionUrl) {
            if (!complete) {
                return MediaWrite.KEEP;
            }
            return attributionUrl
                    .flatMap(
                            sourceUrl ->
                                    FeaturedMediaPolicy.logo(
                                                    logos.getOrDefault(providerId, List.of()))
                                            .map(logo -> observe(logo, sourceUrl)))
                    .orElse(MediaWrite.KEEP);
        }

        /** Counts a committed Game by what happened to its logo. */
        void count(MediaWrite logo, Counts counts) {
            if (logo instanceof MediaWrite.Observe) {
                counts.logoObserved++;
            } else if (requested && !complete) {
                counts.logoUnavailable++;
            }
        }
    }

    /** Two provider releases claimed one external reference; the Game is isolated. */
    private static final class DuplicateReleaseReference extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private DuplicateReleaseReference() {
            super("A release needs a unique provider reference");
        }
    }

    private static final class Counts {
        long inspected;
        long created;
        long updated;
        long unchanged;
        long createdReleases;
        long updatedReleases;
        long unchangedReleases;
        long deletedReleases;
        long deferred;
        long failed;
        long popularityObserved;
        long popularityCleared;
        long popularityUnavailable;
        long featuredImageObserved;
        long logoObserved;
        long logoUnavailable;
        ProviderCallStatistics statistics = ProviderCallStatistics.none();
    }
}
