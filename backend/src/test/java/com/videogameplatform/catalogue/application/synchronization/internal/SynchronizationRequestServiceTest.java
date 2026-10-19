package com.videogameplatform.catalogue.application.synchronization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.administration.SynchronizationRequestOutcome;
import com.videogameplatform.catalogue.application.administration.SynchronizationRequestOutcome.Bound;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.OwnedRun;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationStart;
import com.videogameplatform.catalogue.application.synchronization.SynchronizeCatalogueUseCase;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class SynchronizationRequestServiceTest {
    private static final UUID RUN = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final Instant STARTED = Instant.parse("2026-10-18T10:15:30Z");
    private static final LocalDate FROM = LocalDate.parse("2026-07-01");

    private final FakeSynchronization synchronization = new FakeSynchronization();
    private final List<OwnedRun> submitted = new ArrayList<>();
    private final SynchronizationRequestService service =
            new SynchronizationRequestService(synchronization, submitted::add);

    @Test
    void anAcceptedRequestOwnsTheRunAndHandsItToTheBackgroundWithoutExecutingIt() {
        var outcome = service.request(FROM, LocalDate.parse("2026-12-31"));

        assertThat(outcome)
                .isEqualTo(
                        new SynchronizationRequestOutcome.Accepted(
                                RUN, FROM, LocalDate.parse("2026-12-31"), STARTED));
        assertThat(synchronization.requests)
                .containsExactly(
                        new CatalogueSynchronizationRequest(FROM, LocalDate.parse("2026-12-31")));
        assertThat(submitted).hasSize(1);
        assertThat(synchronization.run.executions).isZero();
    }

    @Test
    void theIntervalIsAtMostAHalfYearOfSupportedDaysAndInvalidOnesStartNothing() {
        assertThat(service.request(FROM, FROM.plusDays(183)))
                .isInstanceOf(SynchronizationRequestOutcome.Accepted.class);
        synchronization.requests.clear();

        assertThat(service.request(FROM, FROM.plusDays(184)))
                .isEqualTo(new SynchronizationRequestOutcome.RangeInvalid(Bound.TO));
        assertThat(service.request(FROM, FROM.minusDays(1)))
                .isEqualTo(new SynchronizationRequestOutcome.RangeInvalid(Bound.TO));
        assertThat(service.request(LocalDate.parse("1949-12-31"), LocalDate.parse("1950-01-31")))
                .isEqualTo(new SynchronizationRequestOutcome.RangeInvalid(Bound.FROM));
        assertThat(service.request(LocalDate.parse("2099-12-01"), LocalDate.parse("2100-01-01")))
                .isEqualTo(new SynchronizationRequestOutcome.RangeInvalid(Bound.TO));
        assertThat(service.request(LocalDate.parse("2100-01-01"), LocalDate.parse("2100-01-02")))
                .isEqualTo(new SynchronizationRequestOutcome.RangeInvalid(Bound.FROM));
        assertThat(service.request(null, FROM))
                .isEqualTo(new SynchronizationRequestOutcome.RangeInvalid(Bound.FROM));
        assertThat(service.request(FROM, null))
                .isEqualTo(new SynchronizationRequestOutcome.RangeInvalid(Bound.TO));
        assertThat(service.request(LocalDate.MIN, LocalDate.MAX))
                .isEqualTo(new SynchronizationRequestOutcome.RangeInvalid(Bound.FROM));
        assertThat(synchronization.requests).isEmpty();
        assertThat(submitted).hasSize(1);
    }

    @Test
    void aRunningOrDisabledSynchronizationIsRejectedAndNothingIsSubmitted() {
        synchronization.skip = SynchronizationStart.Reason.ALREADY_RUNNING;
        assertThat(service.request(FROM, FROM))
                .isEqualTo(new SynchronizationRequestOutcome.AlreadyRunning());

        synchronization.skip = SynchronizationStart.Reason.DISABLED;
        assertThat(service.request(FROM, FROM))
                .isEqualTo(new SynchronizationRequestOutcome.Disabled());

        assertThat(submitted).isEmpty();
    }

    @Test
    void aRunTheBackgroundCannotAcceptReleasesOwnershipAtOnceAndFails() {
        var rejecting =
                new SynchronizationRequestService(
                        synchronization,
                        run -> {
                            throw new RejectedExecutionException("stopping");
                        });

        assertThatThrownBy(() -> rejecting.request(FROM, FROM))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat(synchronization.run.cancellations).isEqualTo(1);
        assertThat(synchronization.run.executions).isZero();
    }

    private static CatalogueSynchronizationReport report(
            CatalogueSynchronizationRequest request, SynchronizationOutcome outcome, String code) {
        return new CatalogueSynchronizationReport(
                null,
                request.from(),
                request.to(),
                STARTED,
                STARTED,
                outcome,
                code,
                new CatalogueSynchronizationReport.Counters(
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        0, 0, 0));
    }

    private static final class FakeSynchronization implements SynchronizeCatalogueUseCase {
        private final List<CatalogueSynchronizationRequest> requests = new ArrayList<>();
        private SynchronizationStart.Reason skip;
        private FakeRun run;

        @Override
        public SynchronizationStart start(CatalogueSynchronizationRequest request) {
            requests.add(request);
            if (skip != null) {
                return new SynchronizationStart.Skipped(
                        skip, report(request, SynchronizationOutcome.SKIPPED, skip.name()));
            }
            run = new FakeRun(request);
            return new SynchronizationStart.Started(run);
        }

        @Override
        public CatalogueSynchronizationReport synchronize(CatalogueSynchronizationRequest request) {
            throw new UnsupportedOperationException("A request never synchronizes inline");
        }

        @Override
        public CatalogueSynchronizationReport repairGames(List<String> ids, boolean dryRun) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CatalogueSynchronizationReport> lastRun() {
            return Optional.empty();
        }
    }

    private static final class FakeRun implements OwnedRun {
        private final CatalogueSynchronizationRequest request;
        private int executions;
        private int cancellations;

        private FakeRun(CatalogueSynchronizationRequest request) {
            this.request = request;
        }

        @Override
        public UUID runId() {
            return RUN;
        }

        @Override
        public CatalogueSynchronizationRequest request() {
            return request;
        }

        @Override
        public Instant startedAt() {
            return STARTED;
        }

        @Override
        public CatalogueSynchronizationReport execute() {
            executions++;
            return report(request, SynchronizationOutcome.SUCCEEDED, "SYNCHRONIZATION_COMPLETED");
        }

        @Override
        public CatalogueSynchronizationReport cancel() {
            cancellations++;
            return report(request, SynchronizationOutcome.FAILED, "SYNCHRONIZATION_FAILED");
        }
    }
}
