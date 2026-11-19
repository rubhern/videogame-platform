package com.videogameplatform.api.delivery.frontend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.catalogue.application.CatalogueReadException;
import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.sharing.GetSharePreviewUseCase;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FrontendRouteControllerTest {
    private static final String TEMPLATE =
            """
            <!doctype html>
            <html lang="es">
              <head>
                <meta charset="UTF-8" />
                <!-- page-metadata:start -->
                <title>Gameómetro</title>
                <!-- page-metadata:end -->
                <script type="module" crossorigin src="/assets/index-abc.js"></script>
              </head>
              <body><div id="root"></div></body>
            </html>
            """;
    private static final String GAME_ID = "30000000-0000-4000-8000-000000000001";
    private static final String PS5 = "10000000-0000-4000-8000-000000000001";

    private final FakePreviews previews = new FakePreviews();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new FrontendRouteController(
                                        new FrontendDocument(TEMPLATE),
                                        new PageMetadataResolver(previews)))
                        .build();
    }

    @Test
    void gameUrlCarriesItsTitleSummaryCoverAndCanonicalUrlWithoutJavaScript() throws Exception {
        previews.games.put(
                GAME_ID,
                game(
                        "Hollow Knight: Silksong",
                        "Explora un reino   gobernado\npor la seda y el canto.",
                        cover()));

        String html = page("/games/" + GAME_ID + "/old-slug", 200);

        assertThat(html)
                .contains("<title>Hollow Knight: Silksong · Gameómetro</title>")
                .contains(
                        "<meta name=\"description\" content=\"Explora un reino gobernado por la"
                                + " seda y el canto.\" />")
                .contains(
                        "<link rel=\"canonical\" href=\"http://localhost/games/"
                                + GAME_ID
                                + "/hollow-knight-silksong\" />")
                .contains(
                        "<meta property=\"og:url\" content=\"http://localhost/games/"
                                + GAME_ID
                                + "/hollow-knight-silksong\" />")
                .contains(
                        "<meta property=\"og:image\" content=\"https://images.igdb.com/igdb/image/"
                                + "upload/t_cover_big_2x/co1abc.jpg\" />")
                .contains("<meta property=\"og:image:alt\" content=\"Portada de Silksong\" />")
                .contains("<meta property=\"og:locale\" content=\"es_ES\" />")
                .contains("<meta property=\"og:type\" content=\"website\" />")
                .contains("<meta name=\"twitter:card\" content=\"summary_large_image\" />")
                .doesNotContain("og:image:width")
                .contains("<script type=\"module\" crossorigin src=\"/assets/index-abc.js\">");
        // The slug-less route resolves to the same canonical page.
        assertThat(page("/games/" + GAME_ID, 200))
                .contains("/games/" + GAME_ID + "/hollow-knight-silksong\" />");
    }

    @Test
    void escapesSpecialCharactersAndKeepsAccentedLettersLiteral() throws Exception {
        previews.games.put(
                GAME_ID,
                game("Ñu \"Quote\" <b>Tag</b> & 'Él'", "<script>alert(1)</script> & más", cover()));

        String html = page("/games/" + GAME_ID + "/x", 200);

        assertThat(html)
                .contains(
                        "<title>Ñu &quot;Quote&quot; &lt;b&gt;Tag&lt;/b&gt; &amp; &#39;Él&#39;"
                                + " · Gameómetro</title>")
                .contains("content=\"&lt;script&gt;alert(1)&lt;/script&gt; &amp; más\"")
                .doesNotContain("<b>Tag")
                .doesNotContain("<script>alert");
    }

    @Test
    void unknownOrMalformedGamesGetGenericTagsAndNotFound() throws Exception {
        for (String path :
                List.of("/games/" + GAME_ID + "/missing", "/games/" + GAME_ID, "/games/pragmata")) {
            String html = page(path, 404);
            assertThat(html)
                    .contains("<title>Gameómetro</title>")
                    .doesNotContain("rel=\"canonical\"")
                    .doesNotContain("og:url")
                    .contains("/assets/share/gameometro.png")
                    // The SPA still loads and shows its own not-found state.
                    .contains("/assets/index-abc.js");
        }
    }

    @Test
    void aFailedCatalogueReadKeepsTheGamePageAvailableWithGenericTags() throws Exception {
        previews.failure = new CatalogueReadException(new RuntimeException("outage"));

        assertThat(page("/games/" + GAME_ID + "/x", 200))
                .contains("<title>Gameómetro</title>")
                .doesNotContain("og:url");
    }

    @Test
    void gameWithoutSummaryOrApprovedCoverUsesTheDescriptionRuleAndDefaultImage() throws Exception {
        previews.games.put(GAME_ID, game("Pragmata", null, new CatalogueCover.Unavailable()));

        assertThat(page("/games/" + GAME_ID + "/pragmata", 200))
                .contains(
                        "content=\"Lanzamientos por plataforma de Pragmata y puntuaciones de la"
                                + " comunidad de Gameómetro.\"")
                .contains(
                        "<meta property=\"og:image\" content=\"http://localhost/assets/share/"
                                + "gameometro.png\" />")
                .contains("<meta property=\"og:image:width\" content=\"1200\" />")
                .contains("<meta property=\"og:image:height\" content=\"630\" />");
    }

    @Test
    void publicPagesDescribeThemselvesWithTheDefaultImage() throws Exception {
        Map<String, String> titles =
                Map.of(
                        "/", "Gameómetro",
                        "/topometro", "TOPómetro · Gameómetro",
                        "/lanzamientos", "Lanzamientos · Gameómetro",
                        "/lanzamientos/destacados", "Destacados · Gameómetro",
                        "/lanzamientos/calendario", "Calendario de lanzamientos · Gameómetro",
                        "/plataformas", "Plataformas · Gameómetro");
        for (var entry : titles.entrySet()) {
            String html = page(entry.getKey() + "?q=ignored", 200);
            assertThat(html)
                    .contains("<title>" + entry.getValue() + "</title>")
                    // The canonical URL never carries a query string.
                    .contains(
                            "<link rel=\"canonical\" href=\"http://localhost"
                                    + entry.getKey()
                                    + "\" />")
                    .contains("http://localhost/assets/share/gameometro.png");
        }
    }

    @Test
    void platformPagesAreNamedAfterTheirPlatform() throws Exception {
        previews.platforms.put(PS5, "PlayStation 5");

        assertThat(page("/plataformas/playstation-5/catalogo", 200))
                .contains("<title>PlayStation 5 · Explorar catálogo · Gameómetro</title>")
                .contains("href=\"http://localhost/plataformas/playstation-5/catalogo\"");
        assertThat(page("/plataformas/" + PS5 + "/topometro", 200))
                .contains("<title>TOPómetro de PlayStation 5 · Gameómetro</title>");
        assertThat(page("/plataformas/playstation-5/proximos", 200))
                .contains("<title>Próximos destacados de PlayStation 5 · Gameómetro</title>");
        // An unknown key keeps the page's own title; the browser shows its state.
        assertThat(page("/plataformas/unknown-platform/proximos", 200))
                .contains("<title>Próximos destacados · Gameómetro</title>");
    }

    @Test
    void privateAndOtherRoutesGetOnlyGenericTags() throws Exception {
        for (String path :
                List.of(
                        "/search?q=secret",
                        "/mis-puntuaciones",
                        "/mis-puntuaciones/estadisticas",
                        "/mis-plataformas",
                        "/mi-perfil",
                        "/mi-perfil/borrado",
                        "/bienvenida",
                        "/administracion/catalogo?year=1997",
                        "/sobre-gameometro",
                        "/contacto",
                        "/privacidad",
                        "/condiciones",
                        "/aviso-legal",
                        "/cookies")) {
            String html = page(path, 200);
            assertThat(head(html))
                    .isEqualTo(head(page("/cookies", 200)))
                    .contains("<title>Gameómetro</title>")
                    .doesNotContain("secret")
                    .doesNotContain("canonical");
        }
        // Generic routes never read the catalogue, so nothing user-related can reach them.
        assertThat(previews.reads).isZero();
    }

    @Test
    void theDocumentIsPublicRevalidatedAndConditional() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/topometro"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-cache, public"))
                        .andExpect(content().contentType("text/html;charset=UTF-8"))
                        .andReturn();
        String etag = result.getResponse().getHeader("ETag");
        assertThat(etag).matches("\"[0-9a-f]{32}\"");

        mockMvc.perform(get("/topometro").header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void doesNotCaptureServerOwnedRouteRoots() throws Exception {
        mockMvc.perform(get("/api")).andExpect(status().isNotFound());
        mockMvc.perform(get("/auth")).andExpect(status().isNotFound());
        mockMvc.perform(get("/login")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator")).andExpect(status().isNotFound());
        mockMvc.perform(get("/login/oauth2/code/keycloak")).andExpect(status().isNotFound());
        // Only a platform's catalogue, TOPómetro and upcoming highlights live under a platform.
        mockMvc.perform(get("/plataformas/ps5/novedades")).andExpect(status().isNotFound());
        mockMvc.perform(get("/plataformas/PS_5/catalogo")).andExpect(status().isNotFound());
        mockMvc.perform(get("/plataformas/PS_5/topometro")).andExpect(status().isNotFound());
        mockMvc.perform(get("/plataformas/PS_5/proximos")).andExpect(status().isNotFound());
        assertThat(previews.reads).isZero();
    }

    private String page(String path, int expectedStatus) throws Exception {
        return mockMvc.perform(get(path))
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String head(String html) {
        return html.substring(
                html.indexOf(FrontendDocument.START), html.indexOf(FrontendDocument.END));
    }

    private static GetSharePreviewUseCase.GamePreview game(
            String title, String summary, CatalogueCover cover) {
        return new GetSharePreviewUseCase.GamePreview(
                GAME_ID, "hollow-knight-silksong", title, summary, cover);
    }

    private static CatalogueCover cover() {
        return new CatalogueCover.Provider(
                URI.create("https://images.igdb.com/igdb/image/upload/t_cover_big_2x/co1abc.jpg"),
                "Portada de Silksong",
                new CatalogueCover.Attribution(
                        "IGDB", URI.create("https://www.igdb.com/games/silksong")));
    }

    private static final class FakePreviews implements GetSharePreviewUseCase {
        final Map<String, GamePreview> games = new HashMap<>();
        final Map<String, String> platforms = new HashMap<>();
        RuntimeException failure;
        int reads;

        @Override
        public Optional<GamePreview> game(String gameId) {
            reads++;
            if (failure != null) throw failure;
            return Optional.ofNullable(games.get(gameId));
        }

        @Override
        public Optional<String> platformName(String platformId) {
            return Optional.ofNullable(platforms.get(platformId));
        }
    }
}
