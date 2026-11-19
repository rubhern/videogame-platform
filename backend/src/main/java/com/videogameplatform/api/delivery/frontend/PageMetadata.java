package com.videogameplatform.api.delivery.frontend;

import java.util.Objects;

/**
 * What a served page says about itself before any JavaScript runs (ADR-0049): its title,
 * description, canonical path and share image. Link previews read it, and #537 reuses it for
 * search engines. Values are plain text; {@link FrontendDocument} escapes them.
 *
 * @param canonicalPath the page's canonical path, or null for a page that names no canonical URL
 * @param missing whether the route names something the catalogue does not hold (served as 404)
 * @param indexable whether search engines may index the page; false adds {@code noindex} (#537)
 */
record PageMetadata(
        String title,
        String description,
        String canonicalPath,
        Image image,
        boolean missing,
        boolean indexable) {

    static final String SITE_NAME = "Gameómetro";
    static final String SITE_DESCRIPTION =
            "Lanzamientos de videojuegos y puntuaciones de la comunidad.";
    static final Image DEFAULT_IMAGE =
            new Image("/assets/share/gameometro.png", "Gameómetro", 1200, 630);

    PageMetadata {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(image, "image");
    }

    /** The site's own tags, for a public page whose own description cannot be read now. */
    static PageMetadata generic() {
        return new PageMetadata(SITE_NAME, SITE_DESCRIPTION, null, DEFAULT_IMAGE, false, true);
    }

    /**
     * A personal, administration or search route: generic tags, never indexed. Such a page shows
     * nothing of its own to an anonymous visitor, and search results are an unbounded query space.
     */
    static PageMetadata unlisted() {
        return new PageMetadata(SITE_NAME, SITE_DESCRIPTION, null, DEFAULT_IMAGE, false, false);
    }

    /** A listed public page with the default share image. */
    static PageMetadata page(String title, String description, String canonicalPath) {
        return new PageMetadata(title, description, canonicalPath, DEFAULT_IMAGE, false, true);
    }

    /** A route naming something that does not exist: generic tags, served as not found. */
    static PageMetadata notFound() {
        return new PageMetadata(SITE_NAME, SITE_DESCRIPTION, null, DEFAULT_IMAGE, true, false);
    }

    /**
     * A share image: an absolute URL or a path on this origin. Width and height are stated only
     * when they are known; a provider cover's pixel size is not.
     */
    record Image(String url, String alternativeText, Integer width, Integer height) {
        Image {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(alternativeText, "alternativeText");
        }
    }
}
