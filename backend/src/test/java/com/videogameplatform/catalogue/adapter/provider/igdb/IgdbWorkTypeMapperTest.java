package com.videogameplatform.catalogue.adapter.provider.igdb;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.synchronization.port.ProviderWorkType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IgdbWorkTypeMapperTest {

    /** Labels as IGDB returned them on 2026-11-06 for the works named in #456. */
    @ParameterizedTest
    @CsvSource({
        "Main Game,MAIN_GAME",
        "Port,PORT",
        "Expanded Game,EXPANDED_GAME",
        "Bundle,BUNDLE",
        "Mod,MOD"
    })
    void mapsTheProviderLabels(String label, ProviderWorkType expected) {
        assertThat(IgdbWorkTypeMapper.map(label)).isEqualTo(expected);
    }

    @Test
    void keepsAnExpandedGameApartFromAnExpansionThatNeedsItsParent() {
        assertThat(IgdbWorkTypeMapper.map("expanded_game"))
                .isEqualTo(ProviderWorkType.EXPANDED_GAME);
        assertThat(IgdbWorkTypeMapper.map("expansion")).isEqualTo(ProviderWorkType.EXPANSION);
    }

    @Test
    void neverGuessesAnUnknownLabel() {
        assertThat(IgdbWorkTypeMapper.map("Something New")).isEqualTo(ProviderWorkType.UNKNOWN);
        assertThat(IgdbWorkTypeMapper.map(null)).isEqualTo(ProviderWorkType.UNKNOWN);
    }
}
