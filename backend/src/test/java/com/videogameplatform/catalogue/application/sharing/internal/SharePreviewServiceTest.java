package com.videogameplatform.catalogue.application.sharing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.cover.port.ProviderCoverReferenceResolver.ResolvedProviderCover;
import com.videogameplatform.catalogue.application.sharing.port.SharePreviewReadPort;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SharePreviewServiceTest {
    private static final String GAME = "30000000-0000-4000-8000-000000000001";

    private final List<UUID> reads = new ArrayList<>();
    private final SharePreviewService service =
            new SharePreviewService(
                    new SharePreviewReadPort() {
                        @Override
                        public Optional<Game> findGame(UUID gameId) {
                            reads.add(gameId);
                            return Optional.of(
                                    new Game(
                                            gameId.toString(),
                                            "slug",
                                            "Title",
                                            null,
                                            new CatalogueCoverReference.Provider(
                                                    "IGDB",
                                                    "co1",
                                                    "Portada",
                                                    "https://www.igdb.com/games/title")));
                        }

                        @Override
                        public Optional<String> findPlatformName(UUID platformId) {
                            reads.add(platformId);
                            return Optional.of("PlayStation 5");
                        }
                    },
                    (provider, reference, sourceUrl) ->
                            new ResolvedProviderCover(
                                    URI.create("https://images.igdb.com/" + reference + ".jpg"),
                                    "IGDB",
                                    URI.create(sourceUrl)));

    @Test
    void resolvesTheProviderCoverIntoItsShareRendition() {
        var preview = service.game(GAME).orElseThrow();

        assertThat(preview.cover())
                .isEqualTo(
                        new CatalogueCover.Provider(
                                URI.create("https://images.igdb.com/co1.jpg"),
                                "Portada",
                                new CatalogueCover.Attribution(
                                        "IGDB", URI.create("https://www.igdb.com/games/title"))));
    }

    @Test
    void identifiersThatAreNoUuidNameNothingAndNeverReachTheDatabase() {
        assertThat(service.game("pragmata")).isEmpty();
        assertThat(service.game(null)).isEmpty();
        assertThat(service.platformName("playstation-5")).isEmpty();
        assertThat(reads).isEmpty();
    }
}
