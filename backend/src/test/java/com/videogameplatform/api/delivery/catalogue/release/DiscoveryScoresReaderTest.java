package com.videogameplatform.api.delivery.catalogue.release;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.api.generated.model.AvailableRatingSummary;
import com.videogameplatform.api.generated.model.UnavailableRatingStatistics;
import com.videogameplatform.catalogue.application.releases.ExternalPlayerScore;
import com.videogameplatform.ratings.application.RatingStatistics;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DiscoveryScoresReaderTest {
    @Test
    void readsOnlyTheSelectedGamesInOneBatchPerModuleAndKeepsBothSourcesAndOneVote() {
        var ids = Set.of("original", "remake", "unrated", "unknown");
        var catalogueReads = new AtomicInteger();
        var ratingsReads = new AtomicInteger();
        var reader =
                new DiscoveryScoresReader(
                        games -> {
                            assertThat(games).isEqualTo(ids);
                            catalogueReads.incrementAndGet();
                            return Map.of(
                                    "original",
                                    external("original", "87.5", 1),
                                    "remake",
                                    external("remake", "0", 1));
                        },
                        games -> {
                            assertThat(games).isEqualTo(ids);
                            ratingsReads.incrementAndGet();
                            return Map.of(
                                    "original",
                                    Optional.of(
                                            new RatingStatistics.Summary(new BigDecimal("9.0"), 1)),
                                    "remake",
                                    Optional.of(new RatingStatistics.Summary(null, 0)),
                                    "unrated",
                                    Optional.of(new RatingStatistics.Summary(null, 0)),
                                    "unknown",
                                    Optional.empty());
                        });

        var result = reader.read(ids);
        assertThat(result).hasSize(4);
        var both = result.get("original");
        assertThat((AvailableRatingSummary) both.getCommunity())
                .satisfies(
                        s -> {
                            assertThat(s.getMean()).isEqualByComparingTo("9");
                            assertThat(s.getCount()).isEqualTo(1);
                        });
        assertThat(both.getExternal().getValue()).isEqualByComparingTo("8.8");
        assertThat(both.getExternal().getCount()).isEqualTo(1);
        assertThat(both.getExternal().getScale()).isEqualTo(10);
        assertThat(both.getExternal().getAttribution().getSourceUrl())
                .hasToString("https://www.igdb.com/games/original");
        assertThat(both.getExternal().getObservedAt().toInstant())
                .isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(result.get("remake").getExternal().getValue()).isEqualByComparingTo("0.0");
        assertThat(result.get("remake").getExternal().getAttribution().getSourceUrl())
                .hasToString("https://www.igdb.com/games/remake");
        assertThat(result.get("unrated").getExternal()).isNull();
        assertThat(result.get("unknown").getCommunity())
                .isInstanceOf(UnavailableRatingStatistics.class);
        assertThat(catalogueReads.get()).isEqualTo(1);
        assertThat(ratingsReads.get()).isEqualTo(1);
    }

    @Test
    void keepsCommunityOnlyAndDoesNoWorkForAnEmptySelection() {
        var reader =
                new DiscoveryScoresReader(
                        games -> Map.of(),
                        games ->
                                Map.of(
                                        "local",
                                        Optional.of(
                                                new RatingStatistics.Summary(BigDecimal.TEN, 1))));
        assertThat(reader.read(Set.of())).isEmpty();
        assertThat(reader.read(Set.of("local")).get("local").getExternal()).isNull();
    }

    private static ExternalPlayerScore external(String work, String value, long count) {
        return new ExternalPlayerScore(
                ExternalPlayerScore.Source.IGDB,
                new BigDecimal(value),
                count,
                URI.create("https://www.igdb.com/games/" + work),
                Instant.parse("2020-01-01T00:00:00Z"),
                Optional.empty());
    }
}
