package com.videogameplatform.api.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.api.generated.model.FallbackCover;
import com.videogameplatform.api.generated.model.ProviderCover;
import com.videogameplatform.catalogue.application.BrowseReleasesResult;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ReleaseCoverMapperTest {

    private final ReleaseCoverMapper mapper = new ReleaseCoverMapper();

    @Test
    void mapsResolvedProviderCoverWithoutProviderSpecificPolicy() {
        var source =
                new BrowseReleasesResult.ProviderCover(
                        URI.create("https://images.example.test/cover.webp"),
                        "Cover",
                        new BrowseReleasesResult.Attribution(
                                "Provider", URI.create("https://provider.example/game")));

        assertThat(mapper.toResponse(source))
                .isEqualTo(
                        new ProviderCover(
                                "provider",
                                URI.create("https://images.example.test/cover.webp"),
                                "Cover",
                                new com.videogameplatform.api.generated.model.Attribution(
                                        "Provider", URI.create("https://provider.example/game"))));
    }

    @Test
    void ownsTheProductPresentationForAnUnavailableCover() {
        assertThat(mapper.toResponse(new BrowseReleasesResult.UnavailableCover()))
                .isEqualTo(
                        new FallbackCover(
                                "fallback",
                                "/assets/covers/fallback.svg",
                                "Carátula oficial no disponible",
                                null));
    }
}
