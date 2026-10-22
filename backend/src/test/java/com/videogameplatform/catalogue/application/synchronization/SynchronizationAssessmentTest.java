package com.videogameplatform.catalogue.application.synchronization;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport.Counters;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress.Failure;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress.Stage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SynchronizationAssessmentTest {

    private static final String COMPLETED_WITH_FAILURES = "SYNCHRONIZATION_COMPLETED_WITH_FAILURES";

    @Test
    void aCompletedIntervalWhoseOnlyFailuresAreKnownDataQualityIsInformational() {
        assertThat(assess(SynchronizationOutcome.PARTIAL, COMPLETED_WITH_FAILURES, 3, 3))
                .isEqualTo(SynchronizationAssessment.DATA_QUALITY);
    }

    @Test
    void oneFailureOutsideTheKnownDataQualitySetNeedsAttention() {
        assertThat(assess(SynchronizationOutcome.PARTIAL, COMPLETED_WITH_FAILURES, 3, 2))
                .isEqualTo(SynchronizationAssessment.ATTENTION);
    }

    @Test
    void aReportWrittenBeforeTheDataQualityCounterExistedIsNeverDowngraded() {
        assertThat(assess(SynchronizationOutcome.PARTIAL, COMPLETED_WITH_FAILURES, 2, 0))
                .isEqualTo(SynchronizationAssessment.ATTENTION);
    }

    @Test
    void anIntervalInterruptedByAProviderPageNeedsAttentionWhateverItsGameFailures() {
        assertThat(assess(SynchronizationOutcome.PARTIAL, "SYNCHRONIZATION_INTERRUPTED", 1, 0))
                .isEqualTo(SynchronizationAssessment.ATTENTION);
    }

    @Test
    void failedAndOwnershipLostRunsNeedAttentionEvenWhenEveryGameWasDataQuality() {
        assertThat(assess(SynchronizationOutcome.FAILED, "SYNCHRONIZATION_FAILED", 2, 2))
                .isEqualTo(SynchronizationAssessment.ATTENTION);
        assertThat(assess(SynchronizationOutcome.FAILED, "SYNCHRONIZATION_OWNERSHIP_LOST", 1, 0))
                .isEqualTo(SynchronizationAssessment.ATTENTION);
    }

    @Test
    void anOperatorStopIsDeliberateWhetherOrNotGamesWereCommitted() {
        assertThat(assess(SynchronizationOutcome.PARTIAL, "SYNCHRONIZATION_STOPPED", 1, 0))
                .isEqualTo(SynchronizationAssessment.STOPPED);
        assertThat(assess(SynchronizationOutcome.FAILED, "SYNCHRONIZATION_STOPPED", 0, 0))
                .isEqualTo(SynchronizationAssessment.STOPPED);
    }

    @Test
    void successAndExpectedSkipsAreQuiet() {
        assertThat(assess(SynchronizationOutcome.SUCCEEDED, "SYNCHRONIZATION_COMPLETED", 0, 0))
                .isEqualTo(SynchronizationAssessment.CLEAN);
        assertThat(assess(SynchronizationOutcome.SKIPPED, "SYNCHRONIZATION_ALREADY_RUNNING", 0, 0))
                .isEqualTo(SynchronizationAssessment.SKIPPED);
        assertThat(assess(SynchronizationOutcome.SKIPPED, "SYNCHRONIZATION_DISABLED", 0, 0))
                .isEqualTo(SynchronizationAssessment.SKIPPED);
    }

    @Test
    void onlyUnderstoodRecordFailuresAreDataQuality() {
        assertThat(Failure.of(Stage.PROVIDER_GAME, "WORK_NOT_RETURNED").dataQuality()).isTrue();
        assertThat(Failure.of(Stage.RECONCILIATION, "RELEASE_TUPLE_CONFLICT").dataQuality())
                .isTrue();
        assertThat(Failure.of(Stage.RECONCILIATION, "DUPLICATE_RELEASE_REFERENCE").dataQuality())
                .isTrue();
        assertThat(Failure.of(Stage.VALIDATION, "TITLE_MISSING").dataQuality()).isTrue();
        assertThat(Failure.of(Stage.VALIDATION, "RELEASE_DATE_INVALID").dataQuality()).isTrue();
        assertThat(Failure.of(Stage.VALIDATION, "RELEASE_LIMIT_EXCEEDED").dataQuality()).isTrue();

        // The same reason in another stage is a stored-evidence integrity risk, not a record.
        assertThat(Failure.of(Stage.PERSISTENCE, "RELEASE_TUPLE_CONFLICT").dataQuality()).isFalse();
        // A provider outage, a datastore failure or an unreadable record can be systemic.
        assertThat(Failure.of(Stage.PROVIDER_GAME, "PROVIDER_UNAVAILABLE").dataQuality()).isFalse();
        assertThat(Failure.of(Stage.PERSISTENCE, "PERSISTENCE_CONNECTION_FAILED").dataQuality())
                .isFalse();
        assertThat(Failure.of(Stage.VALIDATION, "RECORD_UNREADABLE").dataQuality()).isFalse();
        assertThat(Failure.of(Stage.RECONCILIATION, "RECORD_REJECTED").dataQuality()).isFalse();
        assertThat(
                        new Failure(
                                        Stage.PROVIDER_GAME,
                                        "WORK_NOT_RETURNED",
                                        Optional.of(IllegalStateException.class.getName()))
                                .dataQuality())
                .isFalse();
    }

    private static SynchronizationAssessment assess(
            SynchronizationOutcome outcome, String code, long failed, long dataQuality) {
        return SynchronizationAssessment.of(
                new CatalogueSynchronizationReport(
                        UUID.fromString("10000000-0000-4000-8000-000000000001"),
                        LocalDate.parse("2017-03-01"),
                        LocalDate.parse("2017-03-31"),
                        Instant.parse("2026-10-22T14:18:47Z"),
                        Instant.parse("2026-10-22T14:48:23Z"),
                        outcome,
                        code,
                        new Counters(
                                0,
                                10,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                failed,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                dataQuality)));
    }
}
