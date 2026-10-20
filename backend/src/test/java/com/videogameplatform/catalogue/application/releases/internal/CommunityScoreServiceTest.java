package com.videogameplatform.catalogue.application.releases.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.videogameplatform.catalogue.application.releases.RecordCommunityScoresUseCase;
import com.videogameplatform.catalogue.application.releases.RecordCommunityScoresUseCase.CommunityScore;
import com.videogameplatform.catalogue.application.releases.port.CommunityScoreStore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CommunityScoreServiceTest {

    private static final String GAME = "30000000-0000-4000-8000-00000000000a";

    @Test
    void acceptsOnlyAMeanAtTheShownDecimalWithItsRatingCount() {
        assertThat(new CommunityScore(new BigDecimal("7.5"), 2).mean()).isEqualByComparingTo("7.5");
        assertThat(new CommunityScore(null, 0).ratingCount()).isZero();

        assertThatIllegalArgumentException().isThrownBy(() -> new CommunityScore(null, 3));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommunityScore(new BigDecimal("7.0"), 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommunityScore(new BigDecimal("7.25"), 2));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommunityScore(new BigDecimal("0.9"), 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CommunityScore(new BigDecimal("10.1"), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new CommunityScore(null, -1));
    }

    @Test
    void forwardsAReplacementWhetherItHoldsRatingsOrNone() {
        var store = new RecordingStore();
        var service = new CommunityScoreService(store);

        service.replace(GAME, new CommunityScore(new BigDecimal("8.0"), 3));
        service.replace(GAME, new CommunityScore(null, 0));

        assertThat(store.replaced)
                .containsExactly(
                        new CommunityScore(new BigDecimal("8.0"), 3), new CommunityScore(null, 0));
    }

    @Test
    void boundsTheBackfillBatchItAnswers() {
        var store = new RecordingStore();
        var service = new CommunityScoreService(store);
        List<String> tooMany =
                IntStream.rangeClosed(0, RecordCommunityScoresUseCase.MAX_BATCH)
                        .mapToObj(index -> new UUID(0, index).toString())
                        .toList();

        assertThatIllegalArgumentException().isThrownBy(() -> service.unprojected(tooMany));
        assertThat(service.unprojected(List.of())).isEmpty();
        assertThat(service.unprojected(List.of(GAME, GAME))).containsExactly(GAME);
        assertThat(store.asked).containsExactly(List.of(UUID.fromString(GAME)));
    }

    private static final class RecordingStore implements CommunityScoreStore {
        private final List<CommunityScore> replaced = new ArrayList<>();
        private final List<List<UUID>> asked = new ArrayList<>();

        @Override
        public void replace(UUID gameId, CommunityScore score) {
            replaced.add(score);
        }

        @Override
        public List<UUID> unprojected(List<UUID> gameIds) {
            asked.add(gameIds);
            return Collections.unmodifiableList(gameIds);
        }
    }
}
