package com.videogameplatform.catalogue.adapter.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.adapter.observability.CatalogueSynchronizationMetrics;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.OwnedRun;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BackgroundSynchronizationExecutorTest {
    private static final CatalogueSynchronizationRequest REQUEST =
            new CatalogueSynchronizationRequest(
                    LocalDate.parse("2026-10-01"), LocalDate.parse("2026-12-31"));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ThreadPoolExecutor threads =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(1),
                    Thread.ofPlatform().factory());
    private final BackgroundSynchronizationExecutor executor =
            new BackgroundSynchronizationExecutor(
                    threads, new CatalogueSynchronizationMetrics(registry));

    @AfterEach
    void stop() {
        executor.close();
    }

    @Test
    void theRunExecutesOffTheCallerThreadAndIsRecordedUnderTheAdministrationTrigger()
            throws Exception {
        var executedOn = new CompletableFuture<Thread>();
        executor.submit(
                run(
                        () -> {
                            executedOn.complete(Thread.currentThread());
                            return report(SynchronizationOutcome.PARTIAL);
                        }));

        assertThat(executedOn.get(5, TimeUnit.SECONDS)).isNotSameAs(Thread.currentThread());
        threads.shutdown();
        assertThat(threads.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(
                        registry.get("catalogue.synchronization.trigger")
                                .tags(
                                        "trigger",
                                        "administration",
                                        "policy",
                                        "none",
                                        "outcome",
                                        "partial")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        registry.get("catalogue.synchronization.run")
                                .tags("outcome", "partial")
                                .counter()
                                .count())
                .isEqualTo(1);
    }

    @Test
    void anUnexpectedRunFailureIsContainedAndCountedAsFailed() throws Exception {
        executor.submit(
                run(
                        () -> {
                            throw new IllegalStateException("payload must not be logged");
                        }));

        threads.shutdown();
        assertThat(threads.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(
                        registry.get("catalogue.synchronization.trigger")
                                .tags("trigger", "administration", "outcome", "failed")
                                .counter()
                                .count())
                .isEqualTo(1);
    }

    @Test
    void closingInterruptsTheRunInFlightAndRejectsLaterRuns() throws Exception {
        var running = new CountDownLatch(1);
        var interrupted = new CompletableFuture<Boolean>();
        executor.submit(
                run(
                        () -> {
                            running.countDown();
                            try {
                                Thread.sleep(60_000);
                                interrupted.complete(false);
                            } catch (InterruptedException expected) {
                                interrupted.complete(true);
                            }
                            return report(SynchronizationOutcome.FAILED);
                        }));
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

        executor.close();

        assertThat(interrupted.get(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> executor.submit(run(() -> report(SynchronizationOutcome.FAILED))))
                .isInstanceOf(RejectedExecutionException.class);
    }

    private static OwnedRun run(Supplier<CatalogueSynchronizationReport> work) {
        return new OwnedRun() {
            @Override
            public UUID runId() {
                return UUID.randomUUID();
            }

            @Override
            public CatalogueSynchronizationRequest request() {
                return REQUEST;
            }

            @Override
            public Instant startedAt() {
                return Instant.EPOCH;
            }

            @Override
            public CatalogueSynchronizationReport execute() {
                return work.get();
            }

            @Override
            public CatalogueSynchronizationReport cancel() {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static CatalogueSynchronizationReport report(SynchronizationOutcome outcome) {
        return new CatalogueSynchronizationReport(
                UUID.randomUUID(),
                REQUEST.from(),
                REQUEST.to(),
                Instant.EPOCH,
                Instant.EPOCH,
                outcome,
                "CODE",
                new CatalogueSynchronizationReport.Counters(
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        0, 0, 0));
    }
}
