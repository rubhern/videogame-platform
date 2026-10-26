package com.videogameplatform.catalogue.application.rankings.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.rankings.GameRanking;
import com.videogameplatform.catalogue.application.rankings.RankGamesUseCase.Query;
import com.videogameplatform.catalogue.application.rankings.RankGamesUseCase.Source;
import com.videogameplatform.catalogue.application.rankings.port.GameRankingReadPort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class GameRankingServiceTest {

    // 23:30 UTC is already the next day in Madrid.
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-12T23:30:00Z"), ZoneId.of("Europe/Madrid"));

    @Test
    void continuesPositionsFromTheOffsetSoPagesFormOneSequence() {
        var port = new RecordingPort(3, 23);
        var ranking = service(port).rank(new Query(Source.GAMEOMETRO_COMMUNITY, 3, 10));

        assertThat(ranking.items())
                .extracting(GameRanking.Item::position)
                .containsExactly(21L, 22L, 23L);
        assertThat(ranking.page()).isEqualTo(new GameRanking.PageMetadata(3, 10, 23, 3));
        assertThat(ranking.source()).isEqualTo(Source.GAMEOMETRO_COMMUNITY);
        assertThat(port.asked.getFirst().pagination())
                .isEqualTo(new GameRankingReadPort.Pagination(3, 10, 20));
        assertThat(ranking.items().getFirst().primaryCover())
                .isInstanceOf(CatalogueCover.Product.class);
    }

    @Test
    void evaluatesEligibilityOnTheMadridDateAndBoundsTheSummary() {
        var port = new RecordingPort(0, 0);
        var ranking = service(port).rank(new Query(Source.IGDB_PLAYERS, 1, 20));

        assertThat(port.asked.getFirst().evaluatedOn()).isEqualTo(LocalDate.of(2026, 8, 13));
        assertThat(port.asked.getFirst().summaryPlatformLimit()).isEqualTo(3);
        assertThat(port.asked.getFirst().source()).isEqualTo(Source.IGDB_PLAYERS);
        assertThat(ranking.items()).isEmpty();
        assertThat(ranking.page().totalPages()).isZero();
    }

    @Test
    void reportsACatalogueThatWasNeverPublished() {
        GameRankingReadPort none = criteria -> Optional.empty();
        assertThatThrownBy(() -> service(none).rank(new Query(Source.IGDB_PLAYERS, 1, 20)))
                .isInstanceOf(CatalogueNotReadyException.class);
    }

    @Test
    void rejectsAnUnboundedOrSourcelessQuery() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Query(null, 1, 20));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Query(Source.IGDB_PLAYERS, 0, 20));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Query(Source.IGDB_PLAYERS, 1, 101));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GameRanking.Score.Community(new BigDecimal("8.25"), 2));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GameRanking.Score.Community(new BigDecimal("8.2"), 0));
    }

    private static GameRankingService service(GameRankingReadPort port) {
        return new GameRankingService(
                port,
                new CatalogueCoverPolicy(
                        (provider, reference, sourceUrl) -> {
                            throw new AssertionError("No provider resolution for fallback");
                        }),
                CLOCK);
    }

    private static final class RecordingPort implements GameRankingReadPort {
        private final int pageItems;
        private final long total;
        private final List<Criteria> asked = new ArrayList<>();

        private RecordingPort(int pageItems, long total) {
            this.pageItems = pageItems;
            this.total = total;
        }

        @Override
        public Optional<Result> findRankedGames(Criteria criteria) {
            asked.add(criteria);
            return Optional.of(
                    new Result(
                            "v1",
                            IntStream.range(0, pageItems)
                                    .mapToObj(
                                            index ->
                                                    new Item(
                                                            "game-" + index,
                                                            "game-" + index,
                                                            "Game " + index,
                                                            new CatalogueCoverReference.Product(
                                                                    "/assets/covers/fallback.svg",
                                                                    "Portada"),
                                                            List.of(),
                                                            new GameRanking.ReleaseSummary(
                                                                    List.of(), 0, null, null),
                                                            new GameRanking.Score.Community(
                                                                    new BigDecimal("9.0"), 1)))
                                    .toList(),
                            total));
        }
    }
}
