package com.videogameplatform.catalogue.application.synchronization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport.Counters;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.LogoBatch;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderImage;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderWork;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderWorkBatch;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ReleasePage;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.CoverSelection;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.FeaturedEvidenceWrite;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.FeaturedMediaWrite;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.GameState;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.GameWrite;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.MediaWrite;
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
import com.videogameplatform.catalogue.domain.FeaturedMediaPolicy.ImageKind;
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
    private static final String SOURCE = "https://www.igdb.com/games/featured-game";

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

        var report = service.synchronize(REQUEST);

        assertThat(report.outcome()).isEqualTo(SynchronizationOutcome.SKIPPED);
        assertThat(report.outcomeCode()).isEqualTo("SYNCHRONIZATION_ALREADY_RUNNING");
        assertThat(progress.events).containsExactly("skipped:SYNCHRONIZATION_ALREADY_RUNNING");
        verify(provider, never()).releaseGames(any(), any(), anyLong(), anyInt());
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
                            return new WriteResult(true, false, 0, 0, 0, 0);
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
        doThrow(new SynchronizationWriteException(Reason.OWNERSHIP_LOST))
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "MAIN_GAME,false,true",
        "REMAKE,false,true",
        "REMASTER,false,true",
        "STANDALONE_EXPANSION,false,true",
        "MAIN_GAME,true,false",
        "REMAKE,true,false",
        "ADD_ON,false,false"
    })
    void featuredEvidenceUsesExistingImportTypesAndExcludesEditionsOnlyFromFeatured(
            ProviderWorkType type, boolean edition, boolean eligible) {
        startRun();
        when(provider.releaseGames(any(), any(), anyLong(), anyInt()))
                .thenReturn(new ReleasePage(List.of("2"), 1, 2L, true, ONE_REQUEST));
        known("2", KNOWN_GAME);
        var work =
                new ProviderWork(
                        "2",
                        "Existing game",
                        type,
                        Instant.parse("2026-10-03T00:00:00Z"),
                        Optional.empty(),
                        List.of(),
                        Optional.empty(),
                        List.of(),
                        List.of(),
                        Optional.of(
                                new CatalogueProviderPort.ProviderFeaturedEvidence(
                                        Optional.of(java.time.LocalDate.of(2026, 10, 1)),
                                        edition,
                                        Optional.of(292L))));
        when(provider.fetchWorks(List.of("2")))
                .thenReturn(new ProviderWorkBatch(List.of(work), ONE_REQUEST));
        List<GameWrite> writes = recordWrites();
        service.synchronize(REQUEST);
        assertThat(writes)
                .singleElement()
                .satisfies(
                        w ->
                                assertThat(w.featuredEvidence())
                                        .isEqualTo(
                                                new FeaturedEvidenceWrite.Observe(
                                                        Optional.of(
                                                                java.time.LocalDate.of(
                                                                        2026, 10, 1)),
                                                        eligible,
                                                        Optional.of(292L))));
    }

    @Test
    void selectsTheFeaturedImageAndTheLogoInsideEachGameWrite() {
        startRun();
        when(provider.releaseGames(any(), any(), anyLong(), anyInt()))
                .thenReturn(new ReleasePage(List.of("1"), 1, 1L, true, ONE_REQUEST));
        workWithMedia(
                "1",
                List.of(
                        image(ImageKind.SCREENSHOT, "shot", 1920, 1080, false),
                        image(ImageKind.ARTWORK, "portrait", 1000, 1500, false),
                        image(ImageKind.ARTWORK, "keyart", 2560, 1440, false)),
                Optional.of(SOURCE));
        when(provider.logos(List.of("1")))
                .thenReturn(
                        new LogoBatch(
                                Map.of(
                                        "1",
                                        List.of(
                                                image(ImageKind.LOGO, "boxed", 800, 300, false),
                                                image(ImageKind.LOGO, "clear", 900, 320, true))),
                                ONE_REQUEST));
        List<GameWrite> writes = recordWrites();

        var report = service.synchronize(REQUEST);

        // The wide artwork wins over the earlier wide screenshot; only a transparent logo can
        // stand over artwork without a box around it.
        assertThat(writes.getFirst().media())
                .isEqualTo(
                        new FeaturedMediaWrite(
                                new MediaWrite.Observe(
                                        ImageKind.ARTWORK, "keyart", 2560, 1440, false, SOURCE),
                                new MediaWrite.Observe(
                                        ImageKind.SCREENSHOT, "shot", 1920, 1080, false, SOURCE),
                                new MediaWrite.Observe(
                                        ImageKind.LOGO, "clear", 900, 320, true, SOURCE)));
        assertThat(report.counters().featuredImageObservedGames()).isEqualTo(1);
        assertThat(report.counters().logoObservedGames()).isEqualTo(1);
        assertThat(report.counters().logoUnavailableGames()).isZero();
    }

    @Test
    void missingUnusableOrUnattributedMediaKeepTheLastValidSelection() {
        startRun();
        when(provider.releaseGames(any(), any(), anyLong(), anyInt()))
                .thenReturn(new ReleasePage(List.of("1", "2", "3"), 3, 3L, true, ONE_REQUEST));
        workWithMedia("1", List.of(), Optional.of(SOURCE));
        workWithMedia(
                "2",
                List.of(image(ImageKind.ARTWORK, "animated", 1920, 1080, false, true)),
                Optional.of(SOURCE));
        workWithMedia(
                "3",
                List.of(image(ImageKind.ARTWORK, "unattributed", 1920, 1080, false)),
                Optional.empty());
        when(provider.logos(any()))
                .thenReturn(
                        new LogoBatch(
                                Map.of(
                                        "3",
                                        List.of(image(ImageKind.LOGO, "clear", 900, 320, true))),
                                ONE_REQUEST));
        List<GameWrite> writes = recordWrites();

        var report = service.synchronize(REQUEST);

        assertThat(report.outcome()).isEqualTo(SynchronizationOutcome.SUCCEEDED);
        assertThat(writes).extracting(GameWrite::media).containsOnly(FeaturedMediaWrite.KEEP);
        assertThat(report.counters().featuredImageObservedGames()).isZero();
        assertThat(report.counters().logoObservedGames()).isZero();
        assertThat(report.counters().logoUnavailableGames()).isZero();
    }

    @Test
    void anUnavailableLogoAnswerKeepsEveryLogoAndNeverFailsAGame() {
        startRun();
        when(provider.releaseGames(any(), any(), anyLong(), anyInt()))
                .thenReturn(new ReleasePage(List.of("1", "2"), 2, 2L, true, ONE_REQUEST));
        workWithMedia(
                "1",
                List.of(image(ImageKind.ARTWORK, "keyart", 2560, 1440, false)),
                Optional.of(SOURCE));
        workWithMedia("2", List.of(), Optional.of(SOURCE));
        when(provider.logos(any()))
                .thenThrow(
                        new ProviderRequestException(
                                ProviderFailureCode.PROVIDER_UNAVAILABLE, ONE_REQUEST));
        List<GameWrite> writes = recordWrites();

        var report = service.synchronize(REQUEST);

        assertThat(report.outcome()).isEqualTo(SynchronizationOutcome.SUCCEEDED);
        assertThat(report.counters().createdGames()).isEqualTo(2);
        assertThat(writes).extracting(write -> write.media().logo()).containsOnly(MediaWrite.KEEP);
        // The image comes with the work itself, so a failed logo lookup never holds it back.
        assertThat(writes.getFirst().media().image()).isInstanceOf(MediaWrite.Observe.class);
        assertThat(report.counters().featuredImageObservedGames()).isEqualTo(1);
        assertThat(report.counters().logoUnavailableGames()).isEqualTo(2);
        assertThat(progress.events)
                .containsSubsequence(
                        "pageFetched:1:2",
                        "pageLookupUnavailable:1:2:PROVIDER_LOGOS:PROVIDER_UNAVAILABLE",
                        "gameSucceeded:1/1:CREATED",
                        "gameSucceeded:1/2:CREATED");
    }

    @Test
    void repairNeverAsksForPageLookupsAndKeepsUnavailableEvidenceAndLogo() {
        startRun();
        work("2", ProviderWorkType.MAIN_GAME, "Repaired game", List.of());
        known("2", KNOWN_GAME);
        List<GameWrite> writes = recordWrites();

        var report = service.repairGames(List.of("2"), false);

        assertThat(writes)
                .extracting(GameWrite::featuredEvidence)
                .containsExactly(FeaturedEvidenceWrite.KEEP);
        assertThat(writes).extracting(GameWrite::media).containsExactly(FeaturedMediaWrite.KEEP);
        assertThat(report.counters().popularityUnavailableGames()).isEqualTo(1);
        assertThat(report.counters().logoUnavailableGames()).isZero();
        verify(provider, never()).logos(any());
    }

    private List<GameWrite> recordWrites() {
        List<GameWrite> writes = new ArrayList<>();
        when(store.saveGame(eq(RUN), any()))
                .thenAnswer(
                        invocation -> {
                            GameWrite write = invocation.getArgument(1);
                            writes.add(write);
                            return new WriteResult(write.creating(), false, 0, 0, 0, 0);
                        });
        return writes;
    }

    private void startRun() {
        when(provider.isConfigured()).thenReturn(true);
        when(provider.providerName()).thenReturn("IGDB");
        when(store.beginRun(any(), any(), any(), any())).thenReturn(Optional.of(RUN));
        when(provider.logos(any()))
                .thenReturn(new LogoBatch(Map.of(), ProviderCallStatistics.none()));
    }

    private void workWithMedia(
            String providerId, List<ProviderImage> images, Optional<String> attributionUrl) {
        when(provider.fetchWorks(List.of(providerId)))
                .thenReturn(
                        new ProviderWorkBatch(
                                List.of(
                                        new ProviderWork(
                                                providerId,
                                                "Game " + providerId,
                                                ProviderWorkType.MAIN_GAME,
                                                Instant.parse("2026-05-01T00:00:00Z"),
                                                Optional.empty(),
                                                images,
                                                attributionUrl,
                                                List.of(),
                                                List.of())),
                                ONE_REQUEST));
    }

    private static ProviderImage image(
            ImageKind kind, String reference, int width, int height, boolean transparent) {
        return image(kind, reference, width, height, transparent, false);
    }

    private static ProviderImage image(
            ImageKind kind,
            String reference,
            int width,
            int height,
            boolean transparent,
            boolean animated) {
        return new ProviderImage(kind, reference, width, height, transparent, animated);
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
                    observe("pageRequested:" + page, counters);
                }

                @Override
                public void pageFetched(int page, int games, Counters counters) {
                    observe("pageFetched:" + page + ":" + games, counters);
                }

                @Override
                public void pageLookupUnavailable(
                        int page, int games, Failure failure, Counters counters) {
                    observe(
                            "pageLookupUnavailable:"
                                    + page
                                    + ":"
                                    + games
                                    + ":"
                                    + failure.stage()
                                    + ":"
                                    + failure.reason(),
                            counters);
                }

                @Override
                public void gameSucceeded(
                        int page, int position, GameResult result, Counters counters) {
                    observe("gameSucceeded:" + page + "/" + position + ":" + result, counters);
                }

                @Override
                public void gameFailed(
                        int page,
                        int position,
                        Failure failure,
                        Optional<SynchronizedGameIdentity> game,
                        Counters counters) {
                    game.ifPresent(identity -> identities.put(position, identity));
                    observe(
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
                    observe("pageCompleted:" + page, counters);
                }

                @Override
                public void runFailed(Failure failure, Counters counters) {
                    observe(
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

        private void observe(String event, Counters counters) {
            events.add(event);
            lastCounters = counters;
        }
    }
}
