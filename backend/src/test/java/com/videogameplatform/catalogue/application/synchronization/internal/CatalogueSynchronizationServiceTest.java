package com.videogameplatform.catalogue.application.synchronization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport.Counters;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderWork;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderWorkBatch;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ReleasePage;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.CoverSelection;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.GameState;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.WriteResult;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderCallStatistics;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderFailureCode;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderMappingFailure;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderRequestException;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderWorkType;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationProgress;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationWriteException;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationWriteException.Reason;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizedGameIdentity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogueSynchronizationServiceTest {

    private static final CatalogueSynchronizationRequest REQUEST =
            new CatalogueSynchronizationRequest(
                    LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
    private static final UUID RUN = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID KNOWN_GAME = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID WRITTEN_GAME =
            UUID.fromString("20000000-0000-4000-8000-000000000005");
    private static final UUID BOUNDED_GAME =
            UUID.fromString("20000000-0000-4000-8000-000000000008");
    private static final ProviderCallStatistics ONE_REQUEST = new ProviderCallStatistics(1, 0, 5L);

    private final CatalogueSynchronizationStore store = mock(CatalogueSynchronizationStore.class);
    private final CatalogueProviderPort provider = mock(CatalogueProviderPort.class);
    private final RecordingProgress progress = new RecordingProgress();
    private final CatalogueSynchronizationService service =
            new CatalogueSynchronizationService(
                    store,
                    provider,
                    Clock.systemUTC(),
                    new SynchronizationPolicy(500, 25, 50, Duration.ofMinutes(30)),
                    new CoverSelectionPolicy("/fallback.svg", "Product"),
                    progress);

    @Test
    void disabledProviderDoesNotTouchPersistenceAndReportsADistinctSkip() {
        var report = service.synchronize(REQUEST);

        assertThat(report.outcome()).isEqualTo(SynchronizationOutcome.SKIPPED);
        verifyNoInteractions(store);
        assertThat(progress.events).containsExactly("skipped:SYNCHRONIZATION_DISABLED");
    }

    @Test
    void anAlreadyRunningRunIsASkipThatNeverStarts() {
        when(provider.isConfigured()).thenReturn(true);
        when(store.beginRun(any(), any(), any(), any())).thenReturn(Optional.empty());

        service.synchronize(REQUEST);

        assertThat(progress.events).containsExactly("skipped:SYNCHRONIZATION_ALREADY_RUNNING");
    }

    @Test
    void reportsEachGameWithABoundedStageReasonAndHonestIdentityWithoutChangingTheReport() {
        startRun();
        when(provider.releaseGames(any(), any(), anyLong(), anyInt()))
                .thenReturn(
                        new ReleasePage(
                                List.of("1", "2", "3", "4", "5", "6", "7", "8"),
                                9,
                                8L,
                                true,
                                ONE_REQUEST));
        work("1", ProviderWorkType.MAIN_GAME, "Created game", List.of());
        when(provider.fetchWorks(List.of("2")))
                .thenThrow(
                        new ProviderRequestException(
                                ProviderFailureCode.PROVIDER_UNAVAILABLE, ONE_REQUEST));
        work("3", ProviderWorkType.MAIN_GAME, " ", List.of());
        known("3", KNOWN_GAME);
        work("4", ProviderWorkType.BUNDLE, "Deferred bundle", List.of());
        work("5", ProviderWorkType.MAIN_GAME, "Unwritable game", List.of());
        known("5", WRITTEN_GAME);
        work(
                "6",
                ProviderWorkType.MAIN_GAME,
                "Unreadable game",
                List.of(ProviderMappingFailure.RECORD_UNREADABLE));
        work("7", ProviderWorkType.MAIN_GAME, "Rejected New Game", List.of());
        work("8", ProviderWorkType.MAIN_GAME, "Bounded game", List.of());
        when(store.loadGame(eq("8"), anyInt()))
                .thenThrow(
                        new SynchronizationWriteException(
                                Reason.RELEASE_BOUND_EXCEEDED,
                                SynchronizedGameIdentity.published(BOUNDED_GAME, "bounded-game")));
        when(store.saveGame(eq(RUN), any()))
                .thenAnswer(
                        invocation -> {
                            CatalogueSynchronizationStore.GameWrite write =
                                    invocation.getArgument(1);
                            if (write.gameId().equals(WRITTEN_GAME)) {
                                throw new SynchronizationWriteException(
                                        Reason.PERSISTENCE_TIMEOUT,
                                        new IllegalStateException("driver text SELECT 1"));
                            }
                            if (write.title().equals("Rejected New Game")) {
                                throw new SynchronizationWriteException(
                                        Reason.PERSISTENCE_CONSTRAINT_VIOLATION,
                                        new IllegalStateException("duplicate key private"));
                            }
                            return new WriteResult(true, false, 0, 0, 0);
                        });

        var report = service.synchronize(REQUEST);

        assertThat(report.outcome()).isEqualTo(SynchronizationOutcome.PARTIAL);
        assertThat(report.outcomeCode()).isEqualTo("SYNCHRONIZATION_COMPLETED_WITH_FAILURES");
        assertThat(report.counters().createdGames()).isEqualTo(1);
        assertThat(report.counters().deferredGames()).isEqualTo(1);
        assertThat(report.counters().failedGames()).isEqualTo(6);
        assertThat(progress.events)
                .containsExactly(
                        "started:" + RUN,
                        "pageRequested:1",
                        "pageFetched:1:8",
                        "gameSucceeded:1/1:CREATED",
                        // Before the Game is loaded nothing product-owned exists to name.
                        "gameFailed:1/2:PROVIDER_GAME:PROVIDER_UNAVAILABLE:none",
                        "gameFailed:1/3:VALIDATION:TITLE_MISSING:published:"
                                + KNOWN_GAME
                                + ":published-title",
                        "gameSucceeded:1/4:DEFERRED",
                        "gameFailed:1/5:PERSISTENCE:PERSISTENCE_TIMEOUT:published:"
                                + WRITTEN_GAME
                                + ":published-title",
                        // A new Game fails validation before any identity is assigned.
                        "gameFailed:1/6:VALIDATION:RECORD_UNREADABLE:none",
                        "gameFailed:1/7:PERSISTENCE:PERSISTENCE_CONSTRAINT_VIOLATION:unpublished",
                        "gameFailed:1/8:PERSISTENCE:RELEASE_BOUND_EXCEEDED:published:"
                                + BOUNDED_GAME
                                + ":bounded-game",
                        "pageCompleted:1",
                        "finished:PARTIAL");
        // The identity assigned to a new Game is reported as unpublished, with its own slug.
        SynchronizedGameIdentity assigned = progress.identities.get(7);
        assertThat(assigned.published()).isFalse();
        assertThat(assigned.slug()).isEqualTo("rejected-new-game-" + assigned.gameId());
        assertThat(String.join(" ", progress.events))
                .doesNotContain("driver text", "SELECT", "duplicate key", "Rejected New Game");
        // Progress counters are the same accounting the durable report records.
        assertThat(progress.lastCounters).isEqualTo(report.counters());
        assertThat(progress.finished).isSameAs(report);
    }

    @Test
    void aProviderPageFailureIsARunFailureWithItsProviderCode() {
        startRun();
        when(provider.releaseGames(any(), any(), anyLong(), anyInt()))
                .thenThrow(
                        new ProviderRequestException(
                                ProviderFailureCode.PROVIDER_RATE_LIMITED, ONE_REQUEST));

        var report = service.synchronize(REQUEST);

        assertThat(report.outcome()).isEqualTo(SynchronizationOutcome.FAILED);
        assertThat(report.outcomeCode()).isEqualTo("SYNCHRONIZATION_FAILED");
        assertThat(progress.events)
                .containsExactly(
                        "started:" + RUN,
                        "pageRequested:1",
                        "runFailed:PROVIDER_PAGE:PROVIDER_RATE_LIMITED:-",
                        "finished:FAILED");
    }

    @Test
    void aLostLeaseIsAPersistenceRunFailure() {
        startRun();
        when(provider.releaseGames(any(), any(), anyLong(), anyInt()))
                .thenReturn(new ReleasePage(List.of(), 0, 0L, true, ONE_REQUEST));
        org.mockito.Mockito.doThrow(new SynchronizationWriteException(Reason.OWNERSHIP_LOST))
                .when(store)
                .heartbeat(RUN);

        var report = service.synchronize(REQUEST);

        assertThat(report.outcome()).isEqualTo(SynchronizationOutcome.FAILED);
        assertThat(progress.events)
                .contains("runFailed:PERSISTENCE:OWNERSHIP_LOST:-", "finished:FAILED");
    }

    @Test
    void anUnexpectedFailureNamesOnlyItsTypeAndStillFinishesTheRunBeforePropagating() {
        startRun();
        when(provider.releaseGames(any(), any(), anyLong(), anyInt()))
                .thenReturn(new ReleasePage(List.of("1"), 1, 1L, true, ONE_REQUEST));
        work("1", ProviderWorkType.MAIN_GAME, "Game", List.of());
        when(store.loadGame(eq("1"), anyInt()))
                .thenThrow(new IllegalStateException("connection refused to private-host"));

        assertThatThrownBy(() -> service.synchronize(REQUEST))
                .isInstanceOf(IllegalStateException.class);

        assertThat(progress.events)
                .contains(
                        "runFailed:RUN:UNEXPECTED_FAILURE:java.lang.IllegalStateException",
                        "finished:FAILED");
        assertThat(String.join(" ", progress.events)).doesNotContain("private-host");
    }

    private void startRun() {
        when(provider.isConfigured()).thenReturn(true);
        when(provider.providerName()).thenReturn("IGDB");
        when(store.beginRun(any(), any(), any(), any())).thenReturn(Optional.of(RUN));
    }

    private void work(
            String providerId,
            ProviderWorkType type,
            String title,
            List<ProviderMappingFailure> failures) {
        when(provider.fetchWorks(List.of(providerId)))
                .thenReturn(
                        new ProviderWorkBatch(
                                List.of(
                                        new ProviderWork(
                                                providerId,
                                                title,
                                                type,
                                                Instant.parse("2026-05-01T00:00:00Z"),
                                                Optional.empty(),
                                                List.of(),
                                                failures)),
                                ONE_REQUEST));
    }

    private void known(String providerId, UUID gameId) {
        when(store.loadGame(eq(providerId), anyInt()))
                .thenReturn(
                        Optional.of(
                                new GameState(
                                        gameId,
                                        "Published title",
                                        "published-title",
                                        new CoverSelection.ProductFallback(
                                                "/fallback.svg", "Product", "Published title"),
                                        Map.of())));
    }

    /** Records the event sequence in a compact, bounded vocabulary. */
    private static final class RecordingProgress implements SynchronizationProgress {
        private final List<String> events = new ArrayList<>();
        private final Map<Integer, SynchronizedGameIdentity> identities = new HashMap<>();
        private Counters lastCounters;
        private CatalogueSynchronizationReport finished;

        @Override
        public Run started(
                UUID runId,
                CatalogueSynchronizationRequest request,
                Instant startedAt,
                int providerPageSize) {
            events.add("started:" + runId);
            return new Run() {
                @Override
                public void pageRequested(int page, Counters counters) {
                    record("pageRequested:" + page, counters);
                }

                @Override
                public void pageFetched(int page, int games, Counters counters) {
                    record("pageFetched:" + page + ":" + games, counters);
                }

                @Override
                public void gameSucceeded(
                        int page, int position, GameResult result, Counters counters) {
                    record("gameSucceeded:" + page + "/" + position + ":" + result, counters);
                }

                @Override
                public void gameFailed(
                        int page,
                        int position,
                        Failure failure,
                        Optional<SynchronizedGameIdentity> game,
                        Counters counters) {
                    game.ifPresent(identity -> identities.put(position, identity));
                    record(
                            "gameFailed:"
                                    + page
                                    + "/"
                                    + position
                                    + ":"
                                    + failure.stage()
                                    + ":"
                                    + failure.reason()
                                    + ":"
                                    + game.map(RecordingProgress::identity).orElse("none"),
                            counters);
                }

                @Override
                public void pageCompleted(int page, Counters counters) {
                    record("pageCompleted:" + page, counters);
                }

                @Override
                public void runFailed(Failure failure, Counters counters) {
                    record(
                            "runFailed:"
                                    + failure.stage()
                                    + ":"
                                    + failure.reason()
                                    + ":"
                                    + failure.failureType().orElse("-"),
                            counters);
                }

                @Override
                public void finished(CatalogueSynchronizationReport report) {
                    events.add("finished:" + report.outcome());
                    finished = report;
                }
            };
        }

        @Override
        public void skipped(CatalogueSynchronizationReport report) {
            events.add("skipped:" + report.outcomeCode());
        }

        /** Unpublished identities are random, so only their state is part of the sequence. */
        private static String identity(SynchronizedGameIdentity identity) {
            return identity.published()
                    ? "published:" + identity.gameId() + ":" + identity.slug()
                    : "unpublished";
        }

        private void record(String event, Counters counters) {
            events.add(event);
            lastCounters = counters;
        }
    }
}
