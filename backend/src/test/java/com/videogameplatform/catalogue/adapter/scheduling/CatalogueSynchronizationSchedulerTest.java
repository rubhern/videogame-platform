package com.videogameplatform.catalogue.adapter.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.videogameplatform.catalogue.adapter.observability.CatalogueSynchronizationMetrics;
import com.videogameplatform.catalogue.adapter.scheduling.CatalogueSynchronizationScheduler.Policy;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.SynchronizeCatalogueUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.SimpleTriggerContext;

class CatalogueSynchronizationSchedulerTest {
    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    private static final Policy NEAR = new Policy("0 0 4 * * *", 28, 28);
    private static final Policy UPCOMING = new Policy("0 0 5 * * SUN", 0, 180);
    private final SynchronizeCatalogueUseCase useCase = mock(SynchronizeCatalogueUseCase.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void policiesHaveIndependentCronSchedulesInTheTrustedZoneAndSkipMissedExecutions() {
        var clock = Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), MADRID);
        var tasks = tasks(clock, NEAR, UPCOMING);
        assertThat(tasks.getTriggerTaskList()).hasSize(2);
        var context = new SimpleTriggerContext(clock);
        assertThat(tasks.getTriggerTaskList().get(0).getTrigger().nextExecution(context))
                .isEqualTo(Instant.parse("2026-10-05T02:00:00Z"));
        assertThat(tasks.getTriggerTaskList().get(1).getTrigger().nextExecution(context))
                .isEqualTo(Instant.parse("2026-10-11T03:00:00Z"));
        context.update(
                Instant.parse("2026-10-05T02:00:00Z"),
                Instant.parse("2026-10-05T02:00:00Z"),
                Instant.parse("2026-10-08T06:00:00Z"));
        assertThat(tasks.getTriggerTaskList().getFirst().getTrigger().nextExecution(context))
                .isEqualTo(Instant.parse("2026-10-09T02:00:00Z"));
    }

    @Test
    void eitherPolicyCanBeDisabledWithoutChangingTheOther() {
        var clock = Clock.fixed(Instant.EPOCH, MADRID);
        assertThat(tasks(clock, new Policy("-", 0, 0), UPCOMING).getTriggerTaskList()).hasSize(1);
        assertThat(tasks(clock, NEAR, new Policy("-", 0, 0)).getTriggerTaskList()).hasSize(1);
        assertThat(tasks(clock, new Policy("-", 0, 0), new Policy("-", 0, 0)).getTriggerTaskList())
                .isEmpty();
    }

    @Test
    void movingInclusiveWindowsUseMadridMidnightLeapDayAndDstRatherThanTheHostClock() {
        var now = new AtomicReference<>(Instant.parse("2028-02-28T23:30:00Z"));
        Clock clock =
                new Clock() {
                    public ZoneId getZone() {
                        return MADRID;
                    }

                    public Clock withZone(ZoneId zone) {
                        return Clock.fixed(instant(), zone);
                    }

                    public Instant instant() {
                        return now.get();
                    }
                };
        List<CatalogueSynchronizationRequest> requests = new ArrayList<>();
        when(useCase.synchronize(any()))
                .thenAnswer(
                        call -> {
                            CatalogueSynchronizationRequest request = call.getArgument(0);
                            requests.add(request);
                            return report(
                                    request,
                                    SynchronizationOutcome.SUCCEEDED,
                                    "SYNCHRONIZATION_COMPLETED");
                        });
        var tasks = tasks(clock, NEAR, UPCOMING).getTriggerTaskList();
        tasks.get(0).getRunnable().run();
        tasks.get(1).getRunnable().run();
        now.set(Instant.parse("2028-03-25T23:30:00Z"));
        tasks.get(0).getRunnable().run();
        now.set(Instant.parse("2028-03-26T22:30:00Z"));
        tasks.get(0).getRunnable().run();
        assertThat(requests)
                .containsExactly(
                        window("2028-02-01", "2028-03-28"),
                        window("2028-02-29", "2028-08-27"),
                        window("2028-02-27", "2028-04-23"),
                        window("2028-02-28", "2028-04-24"));
    }

    @ParameterizedTest
    @EnumSource(
            value = SynchronizationOutcome.class,
            names = {"SUCCEEDED", "PARTIAL", "FAILED", "SKIPPED"})
    void recordsFinalOutcomeAndCountersOnceWithoutUnboundedMetricTags(
            SynchronizationOutcome outcome) {
        when(useCase.synchronize(any()))
                .thenAnswer(call -> report(call.getArgument(0), outcome, "BOUNDED_CODE"));
        tasks(Clock.fixed(Instant.EPOCH, MADRID), NEAR, UPCOMING)
                .getTriggerTaskList()
                .getFirst()
                .getRunnable()
                .run();
        String tag = outcome.name().toLowerCase(java.util.Locale.ROOT);
        assertThat(
                        registry.get("catalogue.synchronization.trigger")
                                .tags("trigger", "scheduled", "policy", "near_term", "outcome", tag)
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        registry.get("catalogue.synchronization.trigger.duration")
                                .tags("policy", "near_term", "outcome", tag)
                                .timer()
                                .count())
                .isEqualTo(1);
        assertThat(
                        registry.get("catalogue.synchronization.run")
                                .tag("outcome", tag)
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        registry.get("catalogue.synchronization.run.records")
                                .tag("kind", "updated_games")
                                .summary()
                                .totalAmount())
                .isEqualTo(2);
        assertThat(registry.getMeters())
                .allSatisfy(
                        meter ->
                                assertThat(meter.getId().getTags())
                                        .allSatisfy(
                                                t ->
                                                        assertThat(t.getKey())
                                                                .isIn(
                                                                        "trigger",
                                                                        "policy",
                                                                        "outcome",
                                                                        "kind",
                                                                        "operation",
                                                                        "reason")));
    }

    @Test
    void overlapSkipIsObservableAndLaterInvocationsStillRun() {
        when(useCase.synchronize(any()))
                .thenAnswer(
                        call ->
                                report(
                                        call.getArgument(0),
                                        SynchronizationOutcome.SKIPPED,
                                        "SYNCHRONIZATION_ALREADY_RUNNING"));
        var tasks = tasks(Clock.fixed(Instant.EPOCH, MADRID), NEAR, UPCOMING).getTriggerTaskList();
        tasks.forEach(task -> task.getRunnable().run());
        assertThat(
                        registry.get("catalogue.synchronization.trigger")
                                .tags("policy", "near_term", "outcome", "skipped")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        registry.get("catalogue.synchronization.trigger")
                                .tags("policy", "upcoming", "outcome", "skipped")
                                .counter()
                                .count())
                .isEqualTo(1);
    }

    @Test
    void unexpectedFailureIsContainedLoggedWithoutSecretsAndDoesNotCancelRecurrence() {
        when(useCase.synchronize(any()))
                .thenThrow(new IllegalStateException("private token SQL text"))
                .thenAnswer(
                        call ->
                                report(
                                        call.getArgument(0),
                                        SynchronizationOutcome.SUCCEEDED,
                                        "SYNCHRONIZATION_COMPLETED"));
        var logger = (Logger) LoggerFactory.getLogger(CatalogueSynchronizationScheduler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var runnable =
                    tasks(Clock.fixed(Instant.EPOCH, MADRID), NEAR, UPCOMING)
                            .getTriggerTaskList()
                            .getFirst()
                            .getRunnable();
            assertThatCode(runnable::run).doesNotThrowAnyException();
            runnable.run();
            assertThat(appender.list)
                    .anySatisfy(
                            event ->
                                    assertThat(event.getFormattedMessage())
                                            .contains(
                                                    "near_term",
                                                    "SYNCHRONIZATION_TRIGGER_FAILED",
                                                    "IllegalStateException"));
            assertThat(appender.list)
                    .allSatisfy(
                            event -> {
                                assertThat(event.getFormattedMessage())
                                        .doesNotContain("private token", "SQL text");
                                assertThat(event.getThrowableProxy()).isNull();
                            });
            assertThat(
                            registry.get("catalogue.synchronization.trigger")
                                    .tags("policy", "near_term", "outcome", "failed")
                                    .counter()
                                    .count())
                    .isEqualTo(1);
            assertThat(
                            registry.get("catalogue.synchronization.trigger")
                                    .tags("policy", "near_term", "outcome", "succeeded")
                                    .counter()
                                    .count())
                    .isEqualTo(1);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void rejectsInvalidCronAndNegativeWindowsBeforeAnyProviderWork() {
        assertThatThrownBy(() -> new Policy("wrong", 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Policy("0 0 4 * * *", -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Policy("0 0 4 * * *", 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ScheduledTaskRegistrar tasks(Clock clock, Policy near, Policy upcoming) {
        var registrar = new ScheduledTaskRegistrar();
        new CatalogueSynchronizationScheduler(
                        useCase,
                        new CatalogueSynchronizationMetrics(registry),
                        clock,
                        mock(TaskScheduler.class),
                        near,
                        upcoming)
                .configureTasks(registrar);
        return registrar;
    }

    private static CatalogueSynchronizationRequest window(String from, String to) {
        return new CatalogueSynchronizationRequest(LocalDate.parse(from), LocalDate.parse(to));
    }

    private static CatalogueSynchronizationReport report(
            CatalogueSynchronizationRequest request, SynchronizationOutcome outcome, String code) {
        return new CatalogueSynchronizationReport(
                null,
                request.from(),
                request.to(),
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(2),
                outcome,
                code,
                new CatalogueSynchronizationReport.Counters(
                        0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    }
}
