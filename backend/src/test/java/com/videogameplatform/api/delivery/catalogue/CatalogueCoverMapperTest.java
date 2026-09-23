package com.videogameplatform.api.delivery.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.api.generated.model.FallbackCover;
import com.videogameplatform.api.generated.model.ProviderCover;
import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import java.net.URI;
import org.junit.jupiter.api.Test;

class CatalogueCoverMapperTest {

    private final CatalogueCoverMapper mapper = new CatalogueCoverMapper();

    @Test
    void mapsResolvedProviderCoverWithoutProviderSpecificPolicy() {
        var source =
                new CatalogueCover.Provider(
                        URI.create("https://images.example.test/cover.webp"),
                        "Cover",
                        new CatalogueCover.Attribution(
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
        assertThat(mapper.toResponse(new CatalogueCover.Unavailable()))
                .isEqualTo(
                        new FallbackCover(
                                "fallback",
                                "/assets/covers/fallback.svg",
                                "Carátula oficial no disponible",
                                null));
    }
}
