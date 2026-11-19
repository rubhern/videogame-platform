package com.videogameplatform.catalogue.application.details.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.videogameplatform.catalogue.application.details.IndexableGame;
import com.videogameplatform.catalogue.application.details.ListIndexableGamesUseCase;
import com.videogameplatform.catalogue.application.details.port.IndexableGamesReadPort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IndexableGamesServiceTest {

    private static final String GAME = "3000000a-0000-4000-8000-00000000000b";

    private final RecordingPort port = new RecordingPort();
    private final IndexableGamesService service = new IndexableGamesService(port);

    @Test
    void boundsEveryListingByTheSitemapProtocolLimits() {
        assertThat(service.partitionStarts(10_000)).containsExactly(GAME);
        assertThat(port.calls)
                .containsExactly("starts 10000/" + ListIndexableGamesUseCase.MAX_PARTITIONS);

        assertThatIllegalArgumentException().isThrownBy(() -> service.partitionStarts(0));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                service.partition(
                                        GAME, ListIndexableGamesUseCase.MAX_PARTITION_SIZE + 1));
    }

    @Test
    void onlyACanonicalGameIdentifierNamesAPartition() {
        assertThat(service.partition(GAME, 2))
                .extracting(IndexableGame::gameId)
                .containsExactly(GAME);

        for (var malformed : List.of("not-a-uuid", GAME.toUpperCase(), "", " " + GAME)) {
            assertThat(service.partition(malformed, 2)).isEmpty();
        }
        assertThat(service.partition(null, 2)).isEmpty();
        assertThat(port.calls).containsExactly("partition " + GAME + "/2");
    }

    private static final class RecordingPort implements IndexableGamesReadPort {
        private final List<String> calls = new ArrayList<>();

        @Override
        public List<String> partitionStarts(int partitionSize, int maxPartitions) {
            calls.add("starts " + partitionSize + "/" + maxPartitions);
            return List.of(GAME);
        }

        @Override
        public List<IndexableGame> partition(UUID startGameId, int limit) {
            calls.add("partition " + startGameId + "/" + limit);
            return List.of(new IndexableGame(startGameId.toString(), "death-stranding"));
        }
    }
}
