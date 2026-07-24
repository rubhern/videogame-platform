package com.videogameplatform.tools.igdb.normalization;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.videogameplatform.tools.igdb.PocRuntime;
import com.videogameplatform.tools.igdb.sample.ExpectedCase;

class IgdbNormalizerTest {

    @Test
    void selectsAndNormalizesAnExpansionWithoutMergingItIntoItsParent() throws Exception {
        ExpectedCase expected = new ExpectedCase(
                "DLC-01", "dlc_expansion", "Elden Ring Shadow of the Erdtree",
                "Elden Ring: Shadow of the Erdtree", "expansion", "Elden Ring",
                "PC", "Worldwide", "2024-06-21", "day", "released", "",
                "blocking", URI.create("https://example.test/evidence"));
        IgdbNormalizer normalizer = new IgdbNormalizer(
                PocRuntime.objectMapper(),
                Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC));

        var selection = normalizer.selectProvider(expected, fixture("search-expansion.json"));
        var actual = normalizer.normalize(
                expected,
                selection,
                fixture("details-expansion.json"),
                fixture("releases-expansion.json"));

        assertThat(selection.providerId()).isEqualTo(250616L);
        assertThat(actual.found()).isTrue();
        assertThat(actual.type()).isEqualTo("expansion");
        assertThat(actual.parentTitle()).isEqualTo("Elden Ring");
        assertThat(actual.coverAvailable()).isTrue();
        assertThat(actual.genreAvailable()).isTrue();
        assertThat(actual.companyAvailable()).isTrue();
        assertThat(actual.releases()).singleElement().satisfies(release -> {
            assertThat(release.platform()).isEqualTo("PC (Microsoft Windows)");
            assertThat(release.region()).isEqualTo("Worldwide");
            assertThat(release.releaseDate()).isEqualTo("2024-06-21");
            assertThat(release.datePrecision()).isEqualTo("day");
            assertThat(release.status()).isEqualTo("released");
        });
    }

    @Test
    void resolvesExactTitleDuplicatesUsingTheExpectedPlatform() {
        ExpectedCase expected = new ExpectedCase(
                "LEG-07", "legacy_multiplatform_version", "Mass Effect",
                "Mass Effect", "main_game", "", "Xbox 360", "North America",
                "2007-11-20", "day", "released", "", "blocking",
                URI.create("https://example.test/evidence"));
        String candidates = """
                [
                  {
                    "id": 131436,
                    "name": "Mass Effect",
                    "game_type": {"type": "Main Game"},
                    "platforms": [{"name": "PlayStation 5"}]
                  },
                  {
                    "id": 73,
                    "name": "Mass Effect",
                    "game_type": {"type": "Main Game"},
                    "platforms": [{"name": "Xbox 360"}]
                  }
                ]
                """;
        IgdbNormalizer normalizer = new IgdbNormalizer(PocRuntime.objectMapper());

        var selection = normalizer.selectProvider(expected, candidates);

        assertThat(selection.providerId()).isEqualTo(73L);
        assertThat(selection.exactTitleMatchCount()).isEqualTo(2);
    }

    private String fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/" + name)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
