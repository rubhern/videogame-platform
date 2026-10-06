package com.videogameplatform.catalogue.adapter.scheduling;

import com.videogameplatform.catalogue.adapter.observability.CatalogueSynchronizationMetrics;
import com.videogameplatform.catalogue.adapter.observability.SynchronizationTrigger;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.SynchronizeCatalogueUseCase;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;

/** Inbound acquisition policy; the application still owns reconciliation and durable run ownership. */
public final class CatalogueSynchronizationScheduler implements SchedulingConfigurer {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(CatalogueSynchronizationScheduler.class);
    private static final String TRIGGER_FIELD = "sync.trigger";
    private static final String POLICY_FIELD = "sync.policy";
    private final SynchronizeCatalogueUseCase synchronization;
    private final CatalogueSynchronizationMetrics metrics;
    private final Clock clock;
    private final TaskScheduler scheduler;
    private final Policy nearTerm;
    private final Policy upcoming;

    public CatalogueSynchronizationScheduler(
            SynchronizeCatalogueUseCase synchronization,
            CatalogueSynchronizationMetrics metrics,
            Clock clock,
            TaskScheduler scheduler,
            Policy nearTerm,
            Policy upcoming) {
        this.synchronization = synchronization;
        this.metrics = metrics;
        this.clock = clock;
        this.scheduler = scheduler;
        this.nearTerm = nearTerm;
        this.upcoming = upcoming;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(scheduler);
        register(registrar, SynchronizationTrigger.NEAR_TERM, nearTerm);
        register(registrar, SynchronizationTrigger.UPCOMING, upcoming);
    }

    private void register(
            ScheduledTaskRegistrar registrar, SynchronizationTrigger trigger, Policy policy) {
        if (!ScheduledTaskRegistrar.CRON_DISABLED.equals(policy.cron())) {
            // Lenient cron resumes after completion; no replay of missed runs after downtime.
            registrar.addTriggerTask(
                    () -> synchronize(trigger, policy),
                    new CronTrigger(policy.cron(), clock.getZone()));
        }
    }

    private void synchronize(SynchronizationTrigger trigger, Policy policy) {
        long startedNanos = System.nanoTime();
        SynchronizationOutcome outcome = SynchronizationOutcome.FAILED;
        try {
            // Evaluate once at invocation, including a delayed trigger, in the trusted clock's
            // zone.
            LocalDate today = LocalDate.now(clock);
            var request =
                    new CatalogueSynchronizationRequest(
                            today.minusDays(policy.pastDays()),
                            today.plusDays(policy.futureDays()));
            LOGGER.atInfo()
                    .addKeyValue(TRIGGER_FIELD, trigger.type())
                    .addKeyValue(POLICY_FIELD, trigger.policy())
                    .addKeyValue("sync.window_from", request.from())
                    .addKeyValue("sync.window_to", request.to())
                    .log(
                            "Catalogue synchronization scheduled: policy={} window={}..{}",
                            trigger.policy(),
                            request.from(),
                            request.to());
            var report = synchronization.synchronize(request);
            outcome = report.outcome();
            metrics.recordRun(report);
            LOGGER.atLevel(
                            switch (outcome) {
                                case FAILED -> org.slf4j.event.Level.ERROR;
                                case PARTIAL -> org.slf4j.event.Level.WARN;
                                default -> org.slf4j.event.Level.INFO;
                            })
                    .addKeyValue(TRIGGER_FIELD, trigger.type())
                    .addKeyValue(POLICY_FIELD, trigger.policy())
                    .addKeyValue("sync.outcome", outcome)
                    .addKeyValue("sync.code", report.outcomeCode())
                    .log(
                            "Catalogue synchronization scheduled result: policy={} outcome={} code={}",
                            trigger.policy(),
                            outcome,
                            report.outcomeCode());
        } catch (RuntimeException failure) {
            // In particular, beginRun/completeRun can fail outside the use case's provider
            // handling.
            // Do not log exception messages or stop subsequent scheduled invocations.
            LOGGER.atError()
                    .addKeyValue(TRIGGER_FIELD, trigger.type())
                    .addKeyValue(POLICY_FIELD, trigger.policy())
                    .addKeyValue("sync.code", "SYNCHRONIZATION_TRIGGER_FAILED")
                    .addKeyValue("sync.failure.type", failure.getClass().getSimpleName())
                    .log(
                            "Catalogue synchronization scheduled failure: policy={} code={} type={}",
                            trigger.policy(),
                            "SYNCHRONIZATION_TRIGGER_FAILED",
                            failure.getClass().getSimpleName());
        } finally {
            metrics.recordTrigger(trigger, outcome, startedNanos);
        }
    }

    /** Operational tuning, never a domain invariant; '-' disables just this policy. */
    public record Policy(String cron, int pastDays, int futureDays) {
        public Policy {
            if (cron == null
                    || (!ScheduledTaskRegistrar.CRON_DISABLED.equals(cron)
                            && !CronExpression.isValidExpression(cron))
                    || pastDays < 0
                    || futureDays < 0) {
                throw new IllegalArgumentException("Invalid catalogue scheduling policy");
            }
        }
    }
}
