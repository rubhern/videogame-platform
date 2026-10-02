package com.videogameplatform.catalogue.application.synchronization;

import com.videogameplatform.catalogue.application.synchronization.port.ReleaseStageRepairStore;
import java.util.UUID;

/** Complete-Game repair: stage enrichment and stale-reference removal use normal reconciliation. */
public class ReleaseStageRepair {
    private final ReleaseStageRepairStore store;
    private final SynchronizeCatalogueUseCase synchronization;

    public ReleaseStageRepair(
            ReleaseStageRepairStore store, SynchronizeCatalogueUseCase synchronization) {
        this.store = store;
        this.synchronization = synchronization;
    }

    public ReleaseStageRepairStore.Summary summary() {
        return store.summary();
    }

    public Report repair(UUID after, int limit, boolean dryRun) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Batch size must be 1–100");
        }
        var page = store.knownGamesAfter(after, limit + 1);
        boolean complete = page.size() <= limit;
        var candidates = page.stream().limit(limit).toList();
        var result =
                synchronization.repairGames(
                        candidates.stream()
                                .map(ReleaseStageRepairStore.Candidate::gameReference)
                                .toList(),
                        dryRun);
        // Failed/partial batches retain the cursor. Successful Games replay idempotently;
        // unavailable evidence is preserved and a later retry can repair it.
        boolean succeeded = result.outcome() == SynchronizationOutcome.SUCCEEDED;
        return new Report(
                succeeded && !candidates.isEmpty()
                        ? candidates.getLast().gameId().toString()
                        : after.toString(),
                succeeded && complete,
                dryRun,
                candidates.size(),
                result);
    }

    public record Report(
            String nextAfter,
            boolean complete,
            boolean dryRun,
            int inspected,
            CatalogueSynchronizationReport reconciliation) {}
}
