package com.videogameplatform.catalogue.adapter.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport.Counters;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress.Failure;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress.GameResult;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress.Run;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress.Stage;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizedGameIdentity;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class CatalogueSynchronizationLogTest {

    private static final UUID RUN = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID GAME = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final CatalogueSynchronizationRequest REQUEST =
            new CatalogueSynchronizationRequest(
                    LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"));
    private static final Instant STARTED = Instant.parse("2026-09-23T10:00:00Z");

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final CatalogueSynchronizationLog log = new CatalogueSynchronizationLog(nanos::get);
    private final Logger logger =
            (Logger) LoggerFactory.getLogger(CatalogueSynchronizationLog.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureAllLevels() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void restoreLogger() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    @Test
    void startProgressAndSuccessfulCompletionExposeBoundedLifecycleFields() {
        Run run = log.started(RUN, REQUEST, STARTED, 500);
        run.pageRequested(1, counters(0, 0));
        run.pageFetched(1, 3, counters(0, 0));
        run.gameSucceeded(1, 1, GameResult.CREATED, counters(1, 0));
        advance(Duration.ofSeconds(61));
        run.gameSucceeded(1, 2, GameResult.UNCHANGED, counters(2, 0));
        run.gameSucceeded(1, 3, GameResult.DEFERRED, counters(3, 0));
        run.finished(report(SynchronizationOutcome.SUCCEEDED, "SYNCHRONIZATION_COMPLETED", 0));

        List<ILoggingEvent> info = at(Level.INFO);
        assertThat(info).hasSize(3);
        ILoggingEvent started = info.get(0);
        assertThat(keyValue(started, "sync.run_id")).isEqualTo(RUN);
        assertThat(keyValue(started, "sync.window_from")).isEqualTo(REQUEST.from());
        assertThat(keyValue(started, "sync.window_to")).isEqualTo(REQUEST.to());
        assertThat(started.getFormattedMessage()).contains(RUN.toString(), "2026-01-01");

        ILoggingEvent progress = info.get(1);
        assertThat(keyValue(progress, "sync.phase")).isEqualTo("reconciling");
        assertThat(keyValue(progress, "sync.page")).isEqualTo(1);
        assertThat(keyValue(progress, "sync.page_position")).isEqualTo(2);
        assertThat(keyValue(progress, "sync.page_games")).isEqualTo(3);
        assertThat(keyValue(progress, "sync.games.created")).isEqualTo(2L);
        assertThat(keyValue(progress, "sync.elapsed_ms")).isEqualTo(61_000L);
        assertThat(progress.getFormattedMessage())
                .contains("progress", "phase=reconciling", "game=2/3", "created=2");

        ILoggingEvent finished = info.get(2);
        assertThat(keyValue(finished, "sync.outcome")).isEqualTo(SynchronizationOutcome.SUCCEEDED);
        assertThat(keyValue(finished, "sync.code")).isEqualTo("SYNCHRONIZATION_COMPLETED");
        assertThat(keyValue(finished, "sync.failures")).isEqualTo("none");
        assertThat(finished.getFormattedMessage())
                .contains("finished", "outcome=SUCCEEDED", "code=SYNCHRONIZATION_COMPLETED");

        // Per-Game success is diagnostic detail, never INFO.
        assertThat(at(Level.DEBUG))
                .filteredOn(event -> keyValue(event, "sync.game_result") != null)
                .hasSize(3);
    }

    @Test
    void progressIsThrottledRatherThanEmittedPerGame() {
        Run run = log.started(RUN, REQUEST, STARTED, 500);
        run.pageFetched(1, 500, counters(0, 0));
        for (int position = 1; position <= 500; position++) {
            advance(Duration.ofMillis(300));
            run.gameSucceeded(1, position, GameResult.UPDATED, counters(position, 0));
        }

        // 150 seconds of healthy progress yields a checkpoint every 60 seconds, not 500 lines.
        List<ILoggingEvent> progress =
                at(Level.INFO).stream()
                        .filter(event -> "reconciling".equals(keyValue(event, "sync.phase")))
                        .toList();
        assertThat(progress).hasSize(2);
        assertThat(keyValue(progress.get(0), "sync.elapsed_ms")).isEqualTo(60_000L);
        assertThat(keyValue(progress.get(1), "sync.elapsed_ms")).isEqualTo(120_000L);
    }

    @Test
    void pageCompletionIsANaturalCheckpointButCannotFloodWithSmallPages() {
        Run run = log.started(RUN, REQUEST, STARTED, 2);
        advance(Duration.ofSeconds(11));
        run.pageCompleted(1, counters(2, 0));
        advance(Duration.ofSeconds(2));
        run.pageCompleted(2, counters(4, 0));
        advance(Duration.ofSeconds(9));
        run.pageCompleted(3, counters(6, 0));

        List<ILoggingEvent> checkpoints =
                at(Level.INFO).stream()
                        .filter(event -> "page_completed".equals(keyValue(event, "sync.phase")))
                        .toList();
        assertThat(checkpoints)
                .extracting(event -> keyValue(event, "sync.page"))
                .containsExactly(1, 3);
    }

    @Test
    void gameFailuresAreVisibleWithStageAndReasonThenCappedAndTallied() {
        Run run = log.started(RUN, REQUEST, STARTED, 500);
        run.pageFetched(1, 30, counters(0, 0));
        run.gameFailed(
                1,
                1,
                Failure.of(Stage.PERSISTENCE, "PERSISTENCE_WRITE_FAILED"),
                Optional.of(SynchronizedGameIdentity.published(GAME, "published-game")),
                counters(0, 1));
        for (int position = 2; position <= 25; position++) {
            run.gameFailed(
                    1,
                    position,
                    Failure.of(Stage.PROVIDER_GAME, "PROVIDER_UNAVAILABLE"),
                    Optional.empty(),
                    counters(0, position));
        }
        run.finished(report(SynchronizationOutcome.FAILED, "SYNCHRONIZATION_FAILED", 25));

        ILoggingEvent first = at(Level.WARN).getFirst();
        assertThat(keyValue(first, "sync.failure.stage")).isEqualTo("persistence");
        assertThat(keyValue(first, "sync.failure.reason")).isEqualTo("PERSISTENCE_WRITE_FAILED");
        assertThat(keyValue(first, "sync.game_id")).isEqualTo(GAME);

        List<ILoggingEvent> failureEvents =
                appender.list.stream()
                        .filter(event -> keyValue(event, "sync.failure.reason") != null)
                        .toList();
        assertThat(failureEvents).hasSize(25);
        assertThat(failureEvents)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .hasSize(CatalogueSynchronizationLog.GAME_FAILURE_WARN_LIMIT);
        assertThat(at(Level.WARN))
                .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("DEBUG"));

        ILoggingEvent finished = at(Level.ERROR).getFirst();
        assertThat(keyValue(finished, "sync.outcome")).isEqualTo(SynchronizationOutcome.FAILED);
        assertThat(keyValue(finished, "sync.failures"))
                .isEqualTo(
                        "persistence/PERSISTENCE_WRITE_FAILED=1,"
                                + "provider_game/PROVIDER_UNAVAILABLE=24");
    }

    @Test
    void everyGameFailureStatesItsIdentityAndNeverInventsOne() {
        UUID assigned = UUID.fromString("30000000-0000-4000-8000-000000000009");
        Run run = log.started(RUN, REQUEST, STARTED, 500);
        run.pageFetched(1, 281, counters(0, 0));
        run.gameFailed(
                1,
                104,
                Failure.of(Stage.PERSISTENCE, "PERSISTENCE_TIMEOUT"),
                Optional.of(SynchronizedGameIdentity.published(GAME, "resident-evil-requiem")),
                counters(0, 1));
        run.gameFailed(
                1,
                105,
                Failure.of(Stage.PERSISTENCE, "PERSISTENCE_CONSTRAINT_VIOLATION"),
                Optional.of(SynchronizedGameIdentity.unpublished(assigned, "new-game-" + assigned)),
                counters(0, 2));
        run.gameFailed(
                1,
                106,
                Failure.of(Stage.PROVIDER_GAME, "PROVIDER_UNAVAILABLE"),
                Optional.empty(),
                counters(0, 3));

        List<ILoggingEvent> failures = at(Level.WARN);
        ILoggingEvent published = failures.get(0);
        assertThat(keyValue(published, "sync.game_identity")).isEqualTo("published");
        assertThat(keyValue(published, "sync.game_id")).isEqualTo(GAME);
        assertThat(keyValue(published, "sync.game_slug")).isEqualTo("resident-evil-requiem");
        assertThat(keyValue(published, "sync.games.failed")).isEqualTo(1L);
        assertThat(published.getFormattedMessage())
                .contains(
                        "stage=persistence",
                        "reason=PERSISTENCE_TIMEOUT",
                        "page=1",
                        "game=104/281",
                        "identity=published",
                        "game_id=" + GAME,
                        "game_slug=resident-evil-requiem",
                        "failed_games=1");

        ILoggingEvent unpublished = failures.get(1);
        assertThat(keyValue(unpublished, "sync.game_identity")).isEqualTo("unpublished");
        assertThat(keyValue(unpublished, "sync.game_id")).isEqualTo(assigned);
        assertThat(unpublished.getFormattedMessage())
                .contains("identity=unpublished", "game_slug=new-game-" + assigned);

        ILoggingEvent none = failures.get(2);
        assertThat(keyValue(none, "sync.game_identity")).isEqualTo("none");
        assertThat(keyValue(none, "sync.game_id")).isNull();
        assertThat(keyValue(none, "sync.game_slug")).isNull();
        assertThat(none.getFormattedMessage())
                .contains("identity=none", "game=106/281")
                .doesNotContain("game_id=", "game_slug=");
    }

    @Test
    void runFailureAndOutcomesUseDistinguishableLevels() {
        Run run = log.started(RUN, REQUEST, STARTED, 500);
        run.pageRequested(1, counters(0, 0));
        run.runFailed(
                new Failure(
                        Stage.RUN,
                        "UNEXPECTED_FAILURE",
                        Optional.of(IllegalStateException.class.getName())),
                counters(0, 1));
        run.finished(report(SynchronizationOutcome.PARTIAL, "SYNCHRONIZATION_FAILED", 1));
        log.skipped(report(SynchronizationOutcome.SKIPPED, "SYNCHRONIZATION_ALREADY_RUNNING", 0));

        ILoggingEvent runFailure =
                at(Level.WARN).stream()
                        .filter(event -> keyValue(event, "sync.failure.type") != null)
                        .findFirst()
                        .orElseThrow();
        assertThat(keyValue(runFailure, "sync.failure.stage")).isEqualTo("run");
        assertThat(keyValue(runFailure, "sync.phase")).isEqualTo("provider_page");
        assertThat(keyValue(runFailure, "sync.failure.type"))
                .isEqualTo("java.lang.IllegalStateException");
        assertThat(at(Level.WARN))
                .anySatisfy(
                        event ->
                                assertThat(keyValue(event, "sync.outcome"))
                                        .isEqualTo(SynchronizationOutcome.PARTIAL));
        assertThat(at(Level.INFO))
                .anySatisfy(
                        event -> {
                            assertThat(keyValue(event, "sync.outcome"))
                                    .isEqualTo(SynchronizationOutcome.SKIPPED);
                            assertThat(keyValue(event, "sync.code"))
                                    .isEqualTo("SYNCHRONIZATION_ALREADY_RUNNING");
                        });
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    private List<ILoggingEvent> at(Level level) {
        return appender.list.stream().filter(event -> event.getLevel() == level).toList();
    }

    private static Counters counters(long created, long failed) {
        return new Counters(created * 2, created, 0, 0, 0, 0, 0, 0, failed, created * 2, 0, 10);
    }

    private static CatalogueSynchronizationReport report(
            SynchronizationOutcome outcome, String code, long failed) {
        return new CatalogueSynchronizationReport(
                outcome == SynchronizationOutcome.SKIPPED ? null : RUN,
                REQUEST.from(),
                REQUEST.to(),
                STARTED,
                STARTED.plusSeconds(90),
                outcome,
                code,
                counters(3, failed));
    }

    private static Object keyValue(ILoggingEvent event, String key) {
        return event.getKeyValuePairs() == null
                ? null
                : event.getKeyValuePairs().stream()
                        .filter(pair -> key.equals(pair.key))
                        .map(pair -> pair.value)
                        .findFirst()
                        .orElse(null);
    }
}
