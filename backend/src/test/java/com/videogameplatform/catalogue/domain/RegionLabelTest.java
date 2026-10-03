package com.videogameplatform.catalogue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** A region label is product presentation derived from a provider descriptor, never identity. */
class RegionLabelTest {

    /** The descriptors the real IGDB catalogue carries, and the label a visitor reads for each. */
    @ParameterizedTest
    @CsvSource({
        "worldwide, Mundial",
        "europe, Europa",
        "north_america, Norteamérica",
        "japan, Japón",
        "new_zealand, Nueva Zelanda",
        "korea, Corea",
        "brazil, Brasil",
        "asia, Asia",
        "australia, Australia",
        "china, China"
    })
    void presentsEveryObservedProviderRegionInSpanish(String descriptor, String label) {
        assertThat(RegionLabel.fromDescriptor(descriptor).value()).isEqualTo(label);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Worldwide", "WORLDWIDE", "worldwide", " worldwide "})
    void matchesAnApprovedLabelWhateverTheCaseOrSpacingOfTheDescriptor(String descriptor) {
        assertThat(RegionLabel.fromDescriptor(descriptor).value()).isEqualTo("Mundial");
    }

    @ParameterizedTest
    @ValueSource(strings = {"North America", "north america", "north-america", "NORTH_AMERICA"})
    void treatsUnderscoresHyphensAndSpacesAlikeWhenMatchingAnApprovedLabel(String descriptor) {
        assertThat(RegionLabel.fromDescriptor(descriptor).value()).isEqualTo("Norteamérica");
    }

    @ParameterizedTest
    @CsvSource({
        "nueva_zelanda, Nueva Zelanda",
        "middle_east, Middle East",
        "south-east-asia, South East Asia",
        "NEW_TERRITORY, New Territory",
        "hong   kong, Hong Kong"
    })
    void turnsAnUnmappedTechnicalDescriptorIntoReadableWords(String descriptor, String label) {
        assertThat(RegionLabel.fromDescriptor(descriptor).value()).isEqualTo(label);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Asia-Pacific", "USA", "Hong Kong"})
    void keepsTheSpellingOfAnUnmappedNameTheProviderAlreadyWroteForPeople(String descriptor) {
        assertThat(RegionLabel.fromDescriptor(descriptor).value()).isEqualTo(descriptor);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "___", "-", "\t\n"})
    void namesARegionWithoutADescriptorWithoutExposingItsProviderIdentity(String descriptor) {
        assertThat(RegionLabel.fromDescriptor(descriptor).value()).isEqualTo("Región sin nombre");
    }

    @Test
    void neverPassesAControlCharacterOrSurroundingWhitespaceToTheLabel() {
        assertThat(RegionLabel.fromDescriptor("  south\u0000africa\t").value())
                .isEqualTo("South Africa");
    }

    @Test
    void keepsWorldwideRegionalGroupsAndCountriesDistinctFromEachOtherAndTheSentinel() {
        List<String> labels =
                Stream.of(
                                "worldwide",
                                "europe",
                                "north_america",
                                "asia",
                                "japan",
                                "korea",
                                "china",
                                "brazil",
                                "australia",
                                "new_zealand")
                        .map(descriptor -> RegionLabel.fromDescriptor(descriptor).value())
                        .toList();

        assertThat(labels).doesNotHaveDuplicates().doesNotContain("Sin región confirmada");
    }

    /** A stored label re-derives to itself, so applying the rule twice can never drift. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "worldwide",
                "north_america",
                "new_zealand",
                "asia",
                "middle_east",
                "Asia-Pacific",
                ""
            })
    void aLabelIsAFixedPointOfTheRule(String descriptor) {
        String label = RegionLabel.fromDescriptor(descriptor).value();

        assertThat(RegionLabel.fromDescriptor(label).value()).isEqualTo(label);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "new_zealand", " Europa"})
    void refusesATechnicalOrUntrimmedLabel(String value) {
        assertThatThrownBy(() -> new RegionLabel(value))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
