package com.videogameplatform.catalogue.application.administration;

import java.util.UUID;

/**
 * An operator's control of one identified synchronization run, whatever started it.
 *
 * <p>Both commands name the run they act on and never fall back to whichever run is in flight.
 * Neither kills a worker, interrupts a transaction, rolls back committed Games, retries the run or
 * changes a recurring schedule.
 */
public interface ControlCatalogueSynchronization {

    /**
     * Records a cooperative stop request on the run in flight. Its worker stops at its next safe
     * point, before its next provider page or Game; repeating the request keeps the first time.
     */
    StopRequestOutcome requestStop(UUID runId);

    /**
     * Abandons and fences the run in flight only when its last heartbeat is older than the
     * abandonment period, in the same statement that checks it; a resumed worker can then neither
     * commit Games, record coverage nor finish the run.
     */
    AbandonmentOutcome abandon(UUID runId);
}
