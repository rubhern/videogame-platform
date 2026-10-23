package com.videogameplatform.api.delivery.catalogue.administration;

import com.videogameplatform.api.delivery.ApiRequestException;
import com.videogameplatform.api.generated.HistoricalSynchronizationApi;
import com.videogameplatform.api.generated.model.HistoricalSynchronizationStatus;
import com.videogameplatform.api.generated.model.HistoricalSynchronizationTarget;
import com.videogameplatform.api.generated.model.ProblemCode;
import com.videogameplatform.catalogue.application.administration.HistoricalSynchronization;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Generated transport mapping only; authorization/CSRF are enforced by the BFF security chain. */
@RestController
@RequestMapping("/api/v1")
public class HistoricalSynchronizationController implements HistoricalSynchronizationApi {
    private final HistoricalSynchronization history;

    HistoricalSynchronizationController(HistoricalSynchronization history) {
        this.history = history;
    }

    @Override
    public ResponseEntity<HistoricalSynchronizationStatus> getHistoricalSynchronization() {
        return answer(history::status, 200);
    }

    @Override
    public ResponseEntity<HistoricalSynchronizationStatus> startHistoricalSynchronization(
            String xCsrfToken, HistoricalSynchronizationTarget target) {
        return answer(
                () ->
                        history.start(
                                target.getFrom(),
                                target.getTo(),
                                HistoricalSynchronization.CoveredMonths.valueOf(
                                        target.getCoveredMonths()
                                                .getValue()
                                                .toUpperCase(Locale.ROOT))),
                202);
    }

    @Override
    public ResponseEntity<HistoricalSynchronizationStatus> stopHistoricalSynchronization(
            String xCsrfToken, String backfillId) {
        return answer(() -> history.stop(identity(backfillId)), 202);
    }

    @Override
    public ResponseEntity<HistoricalSynchronizationStatus> resumeHistoricalSynchronization(
            String xCsrfToken, String backfillId) {
        return answer(() -> history.resume(identity(backfillId)), 202);
    }

    private static UUID identity(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException failure) {
            throw new ApiRequestException(
                    ProblemCode.HISTORICAL_SYNCHRONIZATION_NOT_FOUND, "/path/backfillId");
        }
    }

    private static ResponseEntity<HistoricalSynchronizationStatus> answer(
            Supplier<HistoricalSynchronization.Status> operation, int status) {
        try {
            return ResponseEntity.status(status)
                    .cacheControl(CacheControl.noStore())
                    .body(map(operation.get()));
        } catch (HistoricalSynchronization.Rejected rejected) {
            throw new ApiRequestException(
                    switch (rejected.reason()) {
                        case RANGE_INVALID -> ProblemCode.HISTORICAL_SYNCHRONIZATION_RANGE_INVALID;
                        case NOT_FOUND -> ProblemCode.HISTORICAL_SYNCHRONIZATION_NOT_FOUND;
                        case CONFLICT -> ProblemCode.HISTORICAL_SYNCHRONIZATION_CONFLICT;
                    },
                    "/historicalSynchronization");
        }
    }

    private static HistoricalSynchronizationStatus map(HistoricalSynchronization.Status s) {
        var body =
                new HistoricalSynchronizationStatus(
                        HistoricalSynchronizationStatus.StateEnum.fromValue(
                                s.state().name().toLowerCase(Locale.ROOT)));
        if (s.id() == null) return body;
        body.setBackfillId(s.id().toString());
        body.setFrom(s.from());
        body.setTo(s.to());
        body.setCoveredMonths(
                HistoricalSynchronizationStatus.CoveredMonthsEnum.fromValue(
                        s.coveredMonths().name().toLowerCase(Locale.ROOT)));
        if (s.remainingMonths() > 0) {
            body.setCurrentFrom(s.nextFrom());
            body.setCurrentTo(
                    s.currentTo() == null
                            ? (s.nextFrom()
                                            .withDayOfMonth(s.nextFrom().lengthOfMonth())
                                            .isAfter(s.to())
                                    ? s.to()
                                    : s.nextFrom().withDayOfMonth(s.nextFrom().lengthOfMonth()))
                            : s.currentTo());
        }
        if (s.runId() != null) body.setCurrentRunId(s.runId().toString());
        body.setTotalMonths(s.totalMonths());
        body.setCompletedMonths(s.completedMonths());
        body.setSkippedMonths(s.skippedMonths());
        body.setRemainingMonths(s.remainingMonths());
        body.setProgressPercent(s.progressPercent());
        body.setProcessedGames(s.processedGames());
        body.setProcessedReleases(s.processedReleases());
        body.setDataQualityFailedGames(s.dataQualityFailedGames());
        body.setCurrentProcessedGames(s.currentGames());
        body.setCurrentProcessedReleases(s.currentReleases());
        body.setCurrentFailedGames(s.currentFailedGames());
        body.setCurrentDataQualityFailedGames(s.currentDataQualityFailedGames());
        body.setCurrentProviderRequests(s.currentProviderRequests());
        body.setStartedAt(s.startedAt().atOffset(ZoneOffset.UTC));
        body.setLastActivityAt(s.lastActivityAt().atOffset(ZoneOffset.UTC));
        body.setElapsedSeconds(s.elapsedSeconds());
        body.setLastReason(s.lastReason());
        return body;
    }
}
