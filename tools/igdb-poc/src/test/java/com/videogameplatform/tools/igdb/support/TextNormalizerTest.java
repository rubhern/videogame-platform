package com.videogameplatform.tools.igdb.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextNormalizerTest {

    @Test
    void normalizesUnicodePunctuationCaseAndWhitespace() {
        assertThat(TextNormalizer.title("  Lou’s   Lagoon — Deluxe "))
                .isEqualTo("lou's lagoon - deluxe");
    }

    @Test
    void usesExplicitPlatformAndRegionAliases() {
        assertThat(TextNormalizer.platformMatches("PC", "Microsoft Windows")).isTrue();
        assertThat(TextNormalizer.platformMatches("PlayStation 5", "PS5")).isTrue();
        assertThat(TextNormalizer.regionMatches("North America", "north_america")).isTrue();
        assertThat(TextNormalizer.regionMatches("Europe", "Worldwide")).isTrue();
        assertThat(TextNormalizer.regionMatches("Worldwide", "Europe")).isTrue();
        assertThat(TextNormalizer.regionMatches("Unknown", "Worldwide")).isTrue();
        assertThat(TextNormalizer.platformMatches("PlayStation 5", "PlayStation 4")).isFalse();
    }

    @Test
    void matchesControlledTitlePunctuationAndLeadingArticleVariants() {
        assertThat(TextNormalizer.titleMatches(
                "Monster Hunter World: Iceborne",
                "Monster Hunter: World - Iceborne")).isTrue();
        assertThat(TextNormalizer.titleMatches(
                "The Planet Crafter",
                "Planet Crafter")).isTrue();
        assertThat(TextNormalizer.titleMatches(
                "Mass Effect",
                "Mass Effect 2")).isFalse();
    }
}
