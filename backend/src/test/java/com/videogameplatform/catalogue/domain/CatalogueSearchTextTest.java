package com.videogameplatform.catalogue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogueSearchTextTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "Resident Evil 4|resident evil 4",
                "RESIDENT evil 4|resident evil 4",
                "Ghost of Yōtei|ghost of yotei",
                "Réquiem|requiem",
                "  Resident   Evil   |resident evil",
                "Marvel's Wolverine|marvels wolverine",
                "The Witcher IV: Polaris|the witcher iv polaris",
            })
    void normalizesCaseAccentsPunctuationAndSpacing(String source, String expected) {
        assertThat(CatalogueSearchText.of(source).normalized()).isEqualTo(expected);
    }

    @Test
    void foldsEverySymbolRunIntoOneTokenSeparator() {
        assertThat(CatalogueSearchText.of("Xbox Series X|S").normalized())
                .isEqualTo("xbox series x s");
    }

    @Test
    void splitsTheNormalizedFormIntoTheTokensThatMustAllMatch() {
        assertThat(CatalogueSearchText.of("  Resident   Evil 4!  ").tokens())
                .containsExactly("resident", "evil", "4");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "!!!", "--- ...", "\t\n"})
    void treatsTextWithoutAlphanumericContentAsUnsearchable(String source) {
        CatalogueSearchText text = CatalogueSearchText.of(source);

        assertThat(text.isEmpty()).isTrue();
        assertThat(text.normalized()).isEmpty();
    }

    @Test
    void treatsMissingTextAsUnsearchableRatherThanFailing() {
        assertThat(CatalogueSearchText.of(null).isEmpty()).isTrue();
    }

    @Test
    void keepsNonLatinScriptsSearchableInsteadOfDiscardingThem() {
        assertThat(CatalogueSearchText.of("東京 2020").tokens()).containsExactly("東京", "2020");
    }

    @Test
    void leavesLettersWrittenWithAStrokeUnfoldedBecauseTheyAreNotDiacritics() {
        assertThat(CatalogueSearchText.of("Ø").normalized()).isEqualTo("ø");
    }

    @Test
    void neverRewritesTheSourceTitleItself() {
        String title = "Ghost of Yōtei";

        CatalogueSearchText.of(title);

        assertThat(title).isEqualTo("Ghost of Yōtei");
    }
}
