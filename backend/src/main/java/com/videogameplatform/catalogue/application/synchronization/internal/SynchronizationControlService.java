package com.videogameplatform.catalogue.application.synchronization.internal;

import com.videogameplatform.catalogue.application.administration.AbandonmentOutcome;
import com.videogameplatform.catalogue.application.administration.ControlCatalogueSynchronization;
import com.videogameplatform.catalogue.application.administration.StopRequestOutcome;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Records an operator's stop request and abandons a stale run, both on the durable run row that
 * every trigger's ownership already uses.
 *
 * <p>A stop is only a request: the worker acts on it at its next safe point. An abandonment uses
 * the same abandonment period that reports a run stalled and that lets a later run start take its
 * ownership over, judged on the same clock, and PostgreSQL decides it in the statement that fences
 * the run, so no in-process state is trusted.
 */
public final class SynchronizationControlService implements ControlCatalogueSynchronization {
    private final CatalogueSynchronizationStore store;
    private final Clock clock;
    private final SynchronizationPolicy policy;

    public SynchronizationControlService(
            CatalogueSynchronizationStore store, Clock clock, SynchronizationPolicy policy) {
        this.store = store;
        this.clock = clock;
        this.policy = policy;
    }

    @Override
    public StopRequestOutcome requestStop(UUID runId) {
        return store.requestStop(runId, clock.instant());
    }

    @Override
    public AbandonmentOutcome abandon(UUID runId) {
        Instant now = clock.instant();
        return store.abandonRun(runId, now, now.minus(policy.abandonRunAfter()));
    }
}
