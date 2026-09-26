package com.videogameplatform.catalogue.adapter.observability;

import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport.Counters;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizedGameIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Operator log of catalogue synchronization runs.
 *
 * <p>INFO carries the lifecycle: start, a progress checkpoint at least every {@link
 * #PROGRESS_INTERVAL} while events keep arriving (and at provider-page completion), and the final
 * outcome. Per-Game detail is DEBUG. Game failures are WARN up to {@link #GAME_FAILURE_WARN_LIMIT}
 * per run, then DEBUG, and every failure is still tallied by stage and reason in the final event.
 * A Game failure always states its identity: {@code published} or {@code unpublished} with the
 * product Game ID and slug, or {@code none} when the failure preceded any product identity.
 * Each message repeats its bounded fields, so a plain console line carries the same facts as the
 * structured one. Metrics and the durable report remain the aggregate record.
 *
 * <p>Checkpoint spacing uses a monotonic source rather than the product clock, which may be fixed
 * for deterministic tests; the final duration comes from the durable report.
 */
public final class CatalogueSynchronizationLog implements SynchronizationProgress {

    static final Duration PROGRESS_INTERVAL = Duration.ofSeconds(60);
    static final Duration PAGE_CHECKPOINT_MIN_SPACING = Duration.ofSeconds(10);
    static final int GAME_FAILURE_WARN_LIMIT = 20;

    private static final String RUN_ID = "sync.run_id";
    private static final String PHASE = "sync.phase";
    private static final String WINDOW_FROM = "sync.window_from";
    private static final String WINDOW_TO = "sync.window_to";
    private static final String PAGE = "sync.page";
    private static final String PAGE_POSITION = "sync.page_position";
    private static final String GAMES_FAILED = "sync.games.failed";
    private static final String ELAPSED_MS = "sync.elapsed_ms";
    private static final Logger LOGGER = LoggerFactory.getLogger(CatalogueSynchronizationLog.class);

    private final LongSupplier nanoTime;

    public CatalogueSynchronizationLog() {
        this(System::nanoTime);
    }

    CatalogueSynchronizationLog(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    @Override
    public Run started(
            UUID runId,
            CatalogueSynchronizationRequest request,
            Instant startedAt,
            int providerPageSize) {
        LOGGER.atInfo()
                .addKeyValue(RUN_ID, runId)
                .addKeyValue(PHASE, "started")
                .addKeyValue(WINDOW_FROM, request.from())
                .addKeyValue(WINDOW_TO, request.to())
                .addKeyValue("sync.provider_page_size", providerPageSize)
                .log(
                        "Catalogue synchronization started: run={} window={}..{}"
                                + " provider_page_size={}",
                        runId,
                        request.from(),
                        request.to(),
                        providerPageSize);
        return new RunLog(runId);
    }

    @Override
    public void skipped(CatalogueSynchronizationReport report) {
        LOGGER.atInfo()
                .addKeyValue("sync.outcome", report.outcome())
                .addKeyValue("sync.code", report.outcomeCode())
                .addKeyValue(WINDOW_FROM, report.from())
                .addKeyValue(WINDOW_TO, report.to())
                .log(
                        "Catalogue synchronization skipped: outcome={} code={} window={}..{}",
                        report.outcome(),
                        report.outcomeCode(),
                        report.from(),
                        report.to());
    }

    private final class RunLog implements Run {

        private final UUID runId;
        private final long startedNanos;
        private final Map<String, Long> failures = new TreeMap<>();
        private long lastCheckpointNanos;
        private String phase = "started";
        private int page;
        private int pageGames;
        private int position;
        private long gameFailures;

        private RunLog(UUID runId) {
            this.runId = runId;
            this.startedNanos = nanoTime.getAsLong();
            this.lastCheckpointNanos = startedNanos;
        }

        @Override
        public void pageRequested(int page, Counters counters) {
            enter("provider_page", page, 0, 0);
            LOGGER.atDebug()
                    .addKeyValue(RUN_ID, runId)
                    .addKeyValue(PAGE, page)
                    .log("Catalogue synchronization page requested: run={} page={}", runId, page);
            checkpointIfDue(counters, PROGRESS_INTERVAL);
        }

        @Override
        public void pageFetched(int page, int games, Counters counters) {
            enter("reconciling", page, games, 0);
            LOGGER.atDebug()
                    .addKeyValue(RUN_ID, runId)
                    .addKeyValue(PAGE, page)
                    .addKeyValue("sync.page_games", games)
                    .log(
                            "Catalogue synchronization page fetched: run={} page={} games={}",
                            runId,
                            page,
                            games);
            checkpointIfDue(counters, PROGRESS_INTERVAL);
        }

        @Override
        public void gameSucceeded(int page, int position, GameResult result, Counters counters) {
            this.position = position;
            LOGGER.atDebug()
                    .addKeyValue(RUN_ID, runId)
                    .addKeyValue(PAGE, page)
                    .addKeyValue(PAGE_POSITION, position)
                    .addKeyValue("sync.game_result", result)
                    .log(
                            "Catalogue synchronization game {}: run={} page={} game={}/{}",
                            result.name().toLowerCase(Locale.ROOT),
                            runId,
                            page,
                            position,
                            pageGames);
            checkpointIfDue(counters, PROGRESS_INTERVAL);
        }

        @Override
        public void gameFailed(
                int page,
                int position,
                Failure failure,
                Optional<SynchronizedGameIdentity> game,
                Counters counters) {
            this.position = position;
            gameFailures++;
            failures.merge(key(failure), 1L, Long::sum);
            Level level = gameFailures <= GAME_FAILURE_WARN_LIMIT ? Level.WARN : Level.DEBUG;
            LoggingEventBuilder event =
                    LOGGER.atLevel(level)
                            .addKeyValue(RUN_ID, runId)
                            .addKeyValue(PAGE, page)
                            .addKeyValue(PAGE_POSITION, position)
                            .addKeyValue("sync.failure.stage", stage(failure))
                            .addKeyValue("sync.failure.reason", failure.reason())
                            .addKeyValue(GAMES_FAILED, counters.failedGames())
                            .addKeyValue("sync.game_identity", identityState(game));
            game.ifPresent(
                    identity ->
                            event.addKeyValue("sync.game_id", identity.gameId())
                                    .addKeyValue("sync.game_slug", identity.slug()));
            event.log(
                    "Catalogue synchronization game failed: run={} stage={} reason={} page={}"
                            + " game={}/{} identity={}{} failed_games={}",
                    runId,
                    stage(failure),
                    failure.reason(),
                    page,
                    position,
                    pageGames,
                    identityState(game),
                    game.map(
                                    identity ->
                                            " game_id="
                                                    + identity.gameId()
                                                    + " game_slug="
                                                    + identity.slug())
                            .orElse(""),
                    counters.failedGames());
            if (gameFailures == GAME_FAILURE_WARN_LIMIT) {
                LOGGER.atWarn()
                        .addKeyValue(RUN_ID, runId)
                        .addKeyValue(GAMES_FAILED, counters.failedGames())
                        .log(
                                "Catalogue synchronization reached {} Game failures: run={};"
                                        + " further Game failures are logged at DEBUG and"
                                        + " tallied in progress and final events",
                                GAME_FAILURE_WARN_LIMIT,
                                runId);
            }
            checkpointIfDue(counters, PROGRESS_INTERVAL);
        }

        @Override
        public void pageCompleted(int page, Counters counters) {
            this.phase = "page_completed";
            this.page = page;
            this.position = pageGames;
            checkpointIfDue(counters, PAGE_CHECKPOINT_MIN_SPACING);
        }

        @Override
        public void runFailed(Failure failure, Counters counters) {
            failures.merge(key(failure), 1L, Long::sum);
            LoggingEventBuilder event =
                    LOGGER.atWarn()
                            .addKeyValue(RUN_ID, runId)
                            .addKeyValue(PHASE, phase)
                            .addKeyValue(PAGE, page)
                            .addKeyValue("sync.failure.stage", stage(failure))
                            .addKeyValue("sync.failure.reason", failure.reason())
                            .addKeyValue(ELAPSED_MS, elapsed().toMillis());
            failure.failureType().ifPresent(type -> event.addKeyValue("sync.failure.type", type));
            event.log(
                    "Catalogue synchronization run failure: run={} stage={} reason={}{} phase={}"
                            + " page={} elapsed_s={}",
                    runId,
                    stage(failure),
                    failure.reason(),
                    failure.failureType().map(type -> " type=" + type).orElse(""),
                    phase,
                    page,
                    elapsed().toSeconds());
        }

        @Override
        public void finished(CatalogueSynchronizationReport report) {
            Counters c = report.counters();
            long elapsedMillis =
                    Duration.between(report.startedAt(), report.completedAt()).toMillis();
            String failureSummary = failureSummary();
            withCounters(LOGGER.atLevel(level(report.outcome())), c)
                    .addKeyValue(RUN_ID, runId)
                    .addKeyValue("sync.outcome", report.outcome())
                    .addKeyValue("sync.code", report.outcomeCode())
                    .addKeyValue(WINDOW_FROM, report.from())
                    .addKeyValue(WINDOW_TO, report.to())
                    .addKeyValue("sync.pages", page)
                    .addKeyValue(ELAPSED_MS, elapsedMillis)
                    .addKeyValue("sync.releases.created", c.createdReleases())
                    .addKeyValue("sync.releases.updated", c.updatedReleases())
                    .addKeyValue("sync.releases.unchanged", c.unchangedReleases())
                    .addKeyValue("sync.provider.latency_ms", c.providerLatencyMillis())
                    .addKeyValue("sync.failures", failureSummary)
                    .log(
                            "Catalogue synchronization finished: run={} outcome={} code={}"
                                    + " window={}..{} pages={} elapsed_s={} games[created={}"
                                    + " updated={} unchanged={} deferred={} failed={}]"
                                    + " releases[created={} updated={} unchanged={}]"
                                    + " release_dates_inspected={} provider[requests={}"
                                    + " retries={} latency_ms={}] failures={}",
                            runId,
                            report.outcome(),
                            report.outcomeCode(),
                            report.from(),
                            report.to(),
                            page,
                            elapsedMillis / 1000,
                            c.createdGames(),
                            c.updatedGames(),
                            c.unchangedGames(),
                            c.deferredGames(),
                            c.failedGames(),
                            c.createdReleases(),
                            c.updatedReleases(),
                            c.unchangedReleases(),
                            c.inspectedReleaseDates(),
                            c.providerRequests(),
                            c.providerRetries(),
                            c.providerLatencyMillis(),
                            failureSummary);
        }

        private void enter(String phase, int page, int pageGames, int position) {
            this.phase = phase;
            this.page = page;
            this.pageGames = pageGames;
            this.position = position;
        }

        /** One comparison per event; nothing is scheduled and nothing outlives the run. */
        private void checkpointIfDue(Counters counters, Duration spacing) {
            long now = nanoTime.getAsLong();
            if (now - lastCheckpointNanos < spacing.toNanos()) {
                return;
            }
            lastCheckpointNanos = now;
            withCounters(LOGGER.atInfo(), counters)
                    .addKeyValue(RUN_ID, runId)
                    .addKeyValue(PHASE, phase)
                    .addKeyValue(PAGE, page)
                    .addKeyValue(PAGE_POSITION, position)
                    .addKeyValue("sync.page_games", pageGames)
                    .addKeyValue(ELAPSED_MS, elapsed().toMillis())
                    .log(
                            "Catalogue synchronization progress: run={} phase={} page={}"
                                    + " game={}/{} elapsed_s={} games[created={} updated={}"
                                    + " unchanged={} deferred={} failed={}]"
                                    + " release_dates_inspected={} provider[requests={}"
                                    + " retries={}]",
                            runId,
                            phase,
                            page,
                            position,
                            pageGames,
                            elapsed().toSeconds(),
                            counters.createdGames(),
                            counters.updatedGames(),
                            counters.unchangedGames(),
                            counters.deferredGames(),
                            counters.failedGames(),
                            counters.inspectedReleaseDates(),
                            counters.providerRequests(),
                            counters.providerRetries());
        }

        private Duration elapsed() {
            return Duration.ofNanos(nanoTime.getAsLong() - startedNanos);
        }

        private String failureSummary() {
            if (failures.isEmpty()) {
                return "none";
            }
            return failures.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(","));
        }
    }

    private static LoggingEventBuilder withCounters(LoggingEventBuilder event, Counters c) {
        return event.addKeyValue("sync.games.created", c.createdGames())
                .addKeyValue("sync.games.updated", c.updatedGames())
                .addKeyValue("sync.games.unchanged", c.unchangedGames())
                .addKeyValue("sync.games.deferred", c.deferredGames())
                .addKeyValue(GAMES_FAILED, c.failedGames())
                .addKeyValue("sync.release_dates_inspected", c.inspectedReleaseDates())
                .addKeyValue("sync.provider.requests", c.providerRequests())
                .addKeyValue("sync.provider.retries", c.providerRetries());
    }

    private static Level level(SynchronizationOutcome outcome) {
        return switch (outcome) {
            case FAILED -> Level.ERROR;
            case PARTIAL -> Level.WARN;
            case SUCCEEDED, SKIPPED, RUNNING -> Level.INFO;
        };
    }

    private static String identityState(Optional<SynchronizedGameIdentity> game) {
        return game.map(identity -> identity.published() ? "published" : "unpublished")
                .orElse("none");
    }

    private static String stage(Failure failure) {
        return failure.stage().name().toLowerCase(Locale.ROOT);
    }

    private static String key(Failure failure) {
        return stage(failure) + "/" + failure.reason();
    }
}
