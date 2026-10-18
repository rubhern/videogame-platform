package com.videogameplatform.api.delivery.catalogue.administration;

import com.videogameplatform.api.delivery.ApiRequestException;
import com.videogameplatform.api.generated.CatalogueAdministrationApi;
import com.videogameplatform.api.generated.model.CatalogueSynchronizationCoverage;
import com.videogameplatform.api.generated.model.CatalogueSynchronizationStatus;
import com.videogameplatform.api.generated.model.CoveragePeriod;
import com.videogameplatform.api.generated.model.CoverageScan;
import com.videogameplatform.api.generated.model.CoverageStatus;
import com.videogameplatform.api.generated.model.CurrentSynchronizationRun;
import com.videogameplatform.api.generated.model.FinishedSynchronizationRun;
import com.videogameplatform.api.generated.model.ProblemCode;
import com.videogameplatform.api.generated.model.SynchronizationRunCounters;
import com.videogameplatform.api.generated.model.SynchronizationRunResult;
import com.videogameplatform.catalogue.application.acquisition.SynchronizationCoverage;
import com.videogameplatform.catalogue.application.acquisition.SynchronizationCoverageQuery;
import com.videogameplatform.catalogue.application.acquisition.SynchronizationStatus;
import com.videogameplatform.catalogue.application.acquisition.SynchronizationStatusQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only catalogue acquisition evidence for the catalogue operator.
 *
 * <p>The security boundary has already required the operator authority. Both reads use local
 * evidence only; responses are personal operational state and are never stored.
 */
@RestController
@RequestMapping("/api/v1")
public class CatalogueAdministrationController implements CatalogueAdministrationApi {
    private static final String TO_POINTER = "/query/to";

    private final SynchronizationStatusQuery status;
    private final SynchronizationCoverageQuery coverage;

    CatalogueAdministrationController(
            SynchronizationStatusQuery status, SynchronizationCoverageQuery coverage) {
        this.status = status;
        this.coverage = coverage;
    }

    @Override
    public ResponseEntity<CatalogueSynchronizationStatus> getCatalogueSynchronizationStatus() {
        SynchronizationStatus answer = status.status();
        CatalogueSynchronizationStatus body =
                new CatalogueSynchronizationStatus(
                        answer.recentRuns().stream()
                                .map(CatalogueAdministrationController::finishedRun)
                                .toList());
        answer.currentRun().ifPresent(run -> body.setCurrentRun(currentRun(run)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @Override
    public ResponseEntity<CatalogueSynchronizationCoverage> getCatalogueSynchronizationCoverage(
            LocalDate from, LocalDate to) {
        if (to.isBefore(from) || to.equals(LocalDate.MAX)) {
            throw new ApiRequestException(ProblemCode.COVERAGE_RANGE_INVALID, TO_POINTER);
        }
        SynchronizationCoverage answer = coverage.coverage(from, to);
        CatalogueSynchronizationCoverage body =
                new CatalogueSynchronizationCoverage(
                        answer.from(),
                        answer.to(),
                        answer.periods().stream()
                                .map(CatalogueAdministrationController::period)
                                .toList());
        answer.continuesFrom().ifPresent(body::setContinuesFrom);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static CurrentSynchronizationRun currentRun(SynchronizationStatus.CurrentRun run) {
        return new CurrentSynchronizationRun(
                run.runId().toString(),
                run.from(),
                run.to(),
                timestamp(run.startedAt()),
                timestamp(run.lastHeartbeatAt()),
                run.stalled());
    }

    private static FinishedSynchronizationRun finishedRun(SynchronizationStatus.FinishedRun run) {
        FinishedSynchronizationRun mapped =
                new FinishedSynchronizationRun(
                        run.runId().toString(),
                        run.from(),
                        run.to(),
                        timestamp(run.startedAt()),
                        timestamp(run.completedAt()),
                        result(run.result()),
                        run.outcomeCode());
        run.counters().ifPresent(counters -> mapped.setCounters(counters(counters)));
        return mapped;
    }

    private static SynchronizationRunResult result(SynchronizationStatus.Result result) {
        return switch (result) {
            case SUCCEEDED -> SynchronizationRunResult.SUCCEEDED;
            case PARTIAL -> SynchronizationRunResult.PARTIAL;
            case FAILED -> SynchronizationRunResult.FAILED;
            case ABANDONED -> SynchronizationRunResult.ABANDONED;
        };
    }

    private static SynchronizationRunCounters counters(SynchronizationStatus.Counters counters) {
        return new SynchronizationRunCounters(
                counters.createdGames(),
                counters.updatedGames(),
                counters.unchangedGames(),
                counters.deferredGames(),
                counters.failedGames(),
                counters.createdReleases(),
                counters.updatedReleases(),
                counters.unchangedReleases(),
                counters.deletedReleases(),
                counters.providerRequests(),
                counters.providerRetries());
    }

    private static CoveragePeriod period(SynchronizationCoverage.Period period) {
        CoveragePeriod mapped =
                new CoveragePeriod(
                        period.from(),
                        period.to(),
                        period.covered() ? CoverageStatus.COVERED : CoverageStatus.NEVER_COVERED);
        period.lastScan()
                .ifPresent(
                        scan ->
                                mapped.setLastScan(
                                        new CoverageScan(
                                                scan.runId().toString(),
                                                timestamp(scan.scanStartedAt()),
                                                timestamp(scan.scanCompletedAt()),
                                                scan.failedGames())));
        return mapped;
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
