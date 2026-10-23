package com.videogameplatform.catalogue.adapter.scheduling;

import com.videogameplatform.catalogue.adapter.observability.CatalogueSynchronizationLog;
import com.videogameplatform.catalogue.adapter.observability.CatalogueSynchronizationMetrics;
import com.videogameplatform.catalogue.adapter.observability.SynchronizationTrigger;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationAssessment;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.SynchronizeCatalogueUseCase;
import com.videogameplatform.catalogue.application.synchronization.port.HistoricalBackfillStore;
import com.videogameplatform.catalogue.application.synchronization.port.ScheduledSynchronizationStore;
import com.videogameplatform.catalogue.application.synchronization.port.ScheduledSynchronizationStore.Pending;
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
    private final HistoricalBackfillStore ownership;
    private final ScheduledSynchronizationStore pending;
    private final Policy nearTerm;
    private final Policy upcoming;

    public CatalogueSynchronizationScheduler(
            SynchronizeCatalogueUseCase synchronization,
            CatalogueSynchronizationMetrics metrics,
            Clock clock,
            TaskScheduler scheduler,
            Policy nearTerm,
            Policy upcoming,
            HistoricalBackfillStore ownership,
            ScheduledSynchronizationStore pending) {
        this.synchronization = synchronization;
        this.ownership = ownership;
        this.pending = pending;
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
        // The same single scheduler thread drains the two fixed slots, including after restart.
        registrar.addFixedDelayTask(this::drain, 1000);
    }

    private void register(
            ScheduledTaskRegistrar registrar, SynchronizationTrigger trigger, Policy policy) {
        if (!ScheduledTaskRegistrar.CRON_DISABLED.equals(policy.cron())) {
            // Lenient cron resumes after completion; no replay of missed runs after downtime.
            registrar.addTriggerTask(
                    () -> defer(trigger, policy), new CronTrigger(policy.cron(), clock.getZone()));
        }
    }

    private void defer(SynchronizationTrigger trigger, Policy policy) {
        LocalDate today = LocalDate.now(clock);
        pending.defer(
                policyKey(trigger),
                new CatalogueSynchronizationRequest(
                        today.minusDays(policy.pastDays()), today.plusDays(policy.futureDays())),
                clock.instant());
    }

    private static ScheduledSynchronizationStore.Policy policyKey(SynchronizationTrigger trigger) {
        return switch (trigger) {
            case NEAR_TERM -> ScheduledSynchronizationStore.Policy.NEAR_TERM;
            case UPCOMING -> ScheduledSynchronizationStore.Policy.UPCOMING;
            default -> throw new IllegalArgumentException("Not a scheduled policy");
        };
    }

    private void drain() {
        if (Thread.currentThread().isInterrupted()) return;
        try {
            if (pending.pending().isEmpty()) return;
            var reserved = ownership.acquireScheduledTurn();
            if (reserved.isEmpty()) return;
            try (var lease = reserved.get()) {
                if (ScheduledTaskRegistrar.CRON_DISABLED.equals(nearTerm.cron()))
                    pending.cancel(ScheduledSynchronizationStore.Policy.NEAR_TERM);
                if (ScheduledTaskRegistrar.CRON_DISABLED.equals(upcoming.cron()))
                    pending.cancel(ScheduledSynchronizationStore.Policy.UPCOMING);
                // A fixed two-row snapshot gives each policy one turn. New intent waits for
                // the next tick and cannot starve the other policy or erase its newer token.
                for (Pending next : pending.pending()) {
                    if (Thread.currentThread().isInterrupted()) return;
                    lease.check();
                    if (!synchronize(next)) return;
                }
            }
        } catch (RuntimeException failure) {
            // Preserve pending work if its ownership/read/write failed. No payload or raw message.
            LOGGER.atError()
                    .addKeyValue("sync.code", "SYNCHRONIZATION_DEFERRED_ADMISSION_FAILED")
                    .addKeyValue("sync.failure.type", failure.getClass().getSimpleName())
                    .log("Scheduled synchronization admission failed");
        }
    }

    private boolean synchronize(Pending deferred) {
        SynchronizationTrigger trigger =
                deferred.policy() == ScheduledSynchronizationStore.Policy.NEAR_TERM
                        ? SynchronizationTrigger.NEAR_TERM
                        : SynchronizationTrigger.UPCOMING;
        CatalogueSynchronizationRequest request = deferred.window();
        long startedNanos = System.nanoTime();
        SynchronizationOutcome outcome = SynchronizationOutcome.FAILED;
        boolean terminal = false;
        try {
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
            if (Thread.currentThread().isInterrupted()) return false;
            if (outcome == SynchronizationOutcome.SKIPPED
                    && "SYNCHRONIZATION_ALREADY_RUNNING".equals(report.outcomeCode())) return false;
            pending.complete(deferred);
            terminal = true;
            metrics.recordRun(report);
            LOGGER.atLevel(CatalogueSynchronizationLog.resultLevel(report))
                    .addKeyValue(TRIGGER_FIELD, trigger.type())
                    .addKeyValue(POLICY_FIELD, trigger.policy())
                    .addKeyValue("sync.outcome", outcome)
                    .addKeyValue("sync.code", report.outcomeCode())
                    .addKeyValue("sync.assessment", SynchronizationAssessment.of(report).tag())
                    .log(
                            "Catalogue synchronization scheduled result: policy={} outcome={} code={}",
                            trigger.policy(),
                            outcome,
                            report.outcomeCode());
        } catch (RuntimeException failure) {
            if (Thread.currentThread().isInterrupted()) return false;
            // Existing periodic failure policy: a terminal trigger failure waits for the next cron.
            pending.complete(deferred);
            terminal = true;
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
            if (terminal) metrics.recordTrigger(trigger, outcome, startedNanos);
        }
        return true;
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
