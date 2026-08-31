package com.videogameplatform.catalogue.adapter.provider.igdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import java.net.URI;
import org.junit.jupiter.api.Test;

class IgdbCoverReferenceResolverTest {

    private final IgdbCoverReferenceResolver resolver = new IgdbCoverReferenceResolver();

    @Test
    void resolvesApprovedIgdbReferencesUsingTheProviderCdnPolicy() {
        var resolved = resolver.resolve("igdb", "co-safe_1", "https://www.igdb.com/games/example");

        assertThat(resolved.url())
                .isEqualTo(
                        URI.create(
                                "https://images.igdb.com/igdb/image/upload/t_cover_big/co-safe_1.webp"));
        assertThat(resolved.attributionLabel()).isEqualTo("IGDB");
        assertThat(resolved.attributionUrl())
                .isEqualTo(URI.create("https://www.igdb.com/games/example"));
    }

    @Test
    void rejectsReferencesForAnUnapprovedProvider() {
        assertThatThrownBy(
                        () ->
                                resolver.resolve(
                                        "another-provider",
                                        "cover-1",
                                        "https://provider.example/games/example"))
                .isInstanceOf(CatalogueDataInvalidException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsReferencesOutsideTheApprovedIgdbHostAndImageIdShape() {
        assertThatThrownBy(
                        () ->
                                resolver.resolve(
                                        "IGDB", "../cover", "https://www.igdb.com/games/example"))
                .isInstanceOf(CatalogueDataInvalidException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                resolver.resolve(
                                        "IGDB",
                                        "cover-1",
                                        "https://unapproved.example/games/example"))
                .isInstanceOf(CatalogueDataInvalidException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }
}
