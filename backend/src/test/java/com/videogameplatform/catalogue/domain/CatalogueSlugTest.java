package com.videogameplatform.catalogue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Slugs are navigation derived from a title, never identity and never a matching key. */
class CatalogueSlugTest {

    @Test
    void derivesANavigablePathFromADisplayTitle() {
        assertThat(CatalogueSlug.fromTitle("Hollow Knight: Silksong").value())
                .isEqualTo("hollow-knight-silksong");
        assertThat(CatalogueSlug.fromTitle("Ghost of Yōtei").value()).isEqualTo("ghost-of-yotei");
        assertThat(CatalogueSlug.fromTitle("  The Witcher IV  ").value())
                .isEqualTo("the-witcher-iv");
    }

    @Test
    void refusesATitleWithNoNavigableCharacters() {
        assertThatThrownBy(() -> CatalogueSlug.fromTitle("!!!"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CatalogueSlug.fromTitle(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supportsNonLatinTitlesWithoutExposingProviderIdentity() {
        assertThat(
                        CatalogueSlug.fromTitle("星のカービィ")
                                .distinguishedBy("30000000-0000-4000-8000-000000000001")
                                .value())
                .isEqualTo("game-30000000-0000-4000-8000-000000000001");
    }

    /** Two works can share a title, so the internal Game identity breaks the tie deterministically. */
    @Test
    void distinguishesACollidingSlugByItsInternalGameIdentity() {
        CatalogueSlug slug = CatalogueSlug.fromTitle("Fable");

        assertThat(slug.distinguishedBy("30000000-0000-4000-8000-000000000001").value())
                .isEqualTo("fable-30000000-0000-4000-8000-000000000001");
        assertThat(slug.distinguishedBy("30000000-0000-4000-8000-000000000001"))
                .isEqualTo(slug.distinguishedBy("30000000-0000-4000-8000-000000000001"));
    }

    @Test
    void producesOnlyTheShapeThePublishedSchemaAccepts() {
        assertThat(CatalogueSlug.fromTitle("Marvel's Spider-Man 2").value())
                .matches("[a-z0-9]+(?:-[a-z0-9]+)*");
        assertThat(CatalogueSlug.fromTitle("A".repeat(400)).value()).hasSizeLessThanOrEqualTo(180);
    }
}
