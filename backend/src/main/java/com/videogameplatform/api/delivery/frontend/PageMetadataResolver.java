package com.videogameplatform.api.delivery.frontend;

import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.CatalogueReadException;
import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.sharing.GetSharePreviewUseCase;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single owner of per-route page metadata (ADR-0049): which routes describe themselves, with
 * what text, and their canonical paths. Every other route, including every private one, gets
 * {@link PageMetadata#generic()}: no user data ever reaches a page's head.
 */
final class PageMetadataResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(PageMetadataResolver.class);

    /**
     * The four featured platform families of {@code frontend/src/features/platforms/
     * platform-context.ts}: their URL key and the product ID seeded in every environment. Any
     * other platform page names a platform by its own ID.
     */
    private static final Map<String, String> FEATURED_PLATFORM_KEYS =
            Map.of(
                    "nintendo-switch-2", "10000000-0000-4000-8000-000000000002",
                    "playstation-5", "10000000-0000-4000-8000-000000000001",
                    "xbox-series-x-s", "10000000-0000-4000-8000-000000000004",
                    "windows-pc", "10000000-0000-4000-8000-000000000003");

    private final GetSharePreviewUseCase previews;

    PageMetadataResolver(GetSharePreviewUseCase previews) {
        this.previews = Objects.requireNonNull(previews, "previews");
    }

    /** The fixed public pages; their canonical path is the route itself. */
    enum PublicPage {
        HOME(
                "/",
                "Gameómetro",
                "Lanzamientos de videojuegos por plataforma y puntuaciones de la comunidad, en un"
                        + " solo sitio."),
        TOPOMETRO(
                "/topometro",
                "TOPómetro · Gameómetro",
                "Los juegos mejor puntuados por la comunidad de Gameómetro y por los jugadores de"
                        + " IGDB."),
        RELEASES(
                "/lanzamientos",
                "Lanzamientos · Gameómetro",
                "Explora los lanzamientos de videojuegos por fecha y plataforma."),
        FEATURED(
                "/lanzamientos/destacados",
                "Destacados · Gameómetro",
                "Los lanzamientos más esperados de la semana y del mes."),
        CALENDAR(
                "/lanzamientos/calendario",
                "Calendario de lanzamientos · Gameómetro",
                "Los lanzamientos de videojuegos del mes, día a día."),
        PLATFORMS(
                "/plataformas",
                "Plataformas · Gameómetro",
                "El catálogo, el TOPómetro y los próximos destacados de cada plataforma.");

        private final String path;
        private final String title;
        private final String description;

        PublicPage(String path, String title, String description) {
            this.path = path;
            this.title = title;
            this.description = description;
        }

        PageMetadata metadata() {
            return PageMetadata.page(title, description, path);
        }
    }

    /** A platform's own destinations under {@code /plataformas/{key}/}. */
    enum PlatformPage {
        CATALOGUE("catalogo"),
        TOPOMETRO("topometro"),
        UPCOMING("proximos");

        private final String segment;

        PlatformPage(String segment) {
            this.segment = segment;
        }

        static Optional<PlatformPage> of(String segment) {
            for (PlatformPage page : values()) {
                if (page.segment.equals(segment)) return Optional.of(page);
            }
            return Optional.empty();
        }
    }

    PageMetadata publicPage(PublicPage page) {
        return page.metadata();
    }

    /**
     * A platform page, named after the platform when the key resolves. An unresolved key keeps the
     * page's own title: the browser shows its state, so the status stays 200.
     */
    PageMetadata platform(PlatformPage page, String key) {
        String path = "/plataformas/" + key + "/" + page.segment;
        Optional<String> name = platformName(key);
        return switch (page) {
            case CATALOGUE ->
                    PageMetadata.page(
                            name.map(n -> n + " · Explorar catálogo · Gameómetro")
                                    .orElse("Explorar catálogo · Gameómetro"),
                            name.map(n -> "Los juegos de " + n + " en el catálogo de Gameómetro.")
                                    .orElse("Los juegos de una plataforma en Gameómetro."),
                            path);
            case TOPOMETRO ->
                    PageMetadata.page(
                            name.map(n -> "TOPómetro de " + n + " · Gameómetro")
                                    .orElse("TOPómetro · Gameómetro"),
                            "Los juegos"
                                    + name.map(n -> " de " + n).orElse("")
                                    + " mejor puntuados por la comunidad de Gameómetro y por los"
                                    + " jugadores de IGDB.",
                            path);
            case UPCOMING ->
                    PageMetadata.page(
                            name.map(n -> "Próximos destacados de " + n + " · Gameómetro")
                                    .orElse("Próximos destacados · Gameómetro"),
                            "Los próximos lanzamientos más esperados"
                                    + name.map(n -> " de " + n).orElse("")
                                    + ".",
                            path);
        };
    }

    /**
     * A game page: its title, summary, cover and canonical {@code /games/{gameId}/{slug}} path. A
     * game the catalogue does not hold is not found. A failed read keeps the page available with
     * generic tags, since previews are an enhancement of the page, not its content.
     */
    PageMetadata game(String gameId) {
        Optional<GetSharePreviewUseCase.GamePreview> preview;
        try {
            preview = previews.game(gameId);
        } catch (CatalogueReadException | CatalogueDataInvalidException exception) {
            LOGGER.warn(
                    "Game page metadata unavailable; serving generic tags: {}",
                    exception.getClass().getSimpleName());
            return PageMetadata.generic();
        }
        if (preview.isEmpty()) return PageMetadata.notFound();
        var game = preview.get();
        String description =
                game.summary() == null || game.summary().isBlank()
                        ? "Lanzamientos por plataforma de "
                                + game.canonicalTitle()
                                + " y puntuaciones de la comunidad de Gameómetro."
                        : game.summary();
        return new PageMetadata(
                game.canonicalTitle() + " · Gameómetro",
                description,
                "/games/" + game.gameId() + "/" + game.slug(),
                image(game.cover(), game.canonicalTitle()),
                false);
    }

    private Optional<String> platformName(String key) {
        try {
            return previews.platformName(FEATURED_PLATFORM_KEYS.getOrDefault(key, key));
        } catch (CatalogueReadException | CatalogueDataInvalidException exception) {
            LOGGER.warn(
                    "Platform page metadata unavailable; serving its unnamed title: {}",
                    exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Only an approved provider cover is a share image. A product-owned cover is the SVG fallback,
     * which preview clients do not render, so it and a missing cover use the default image.
     */
    private static PageMetadata.Image image(CatalogueCover cover, String title) {
        return cover instanceof CatalogueCover.Provider provider
                ? new PageMetadata.Image(
                        provider.url().toString(),
                        alternative(provider.alternativeText(), title),
                        null,
                        null)
                : PageMetadata.DEFAULT_IMAGE;
    }

    private static String alternative(String alternativeText, String title) {
        return alternativeText == null || alternativeText.isBlank()
                ? "Portada de " + title
                : alternativeText;
    }
}
