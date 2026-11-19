package com.videogameplatform.api.delivery.frontend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.catalogue.application.details.IndexableGame;
import com.videogameplatform.catalogue.application.details.ListIndexableGamesUseCase;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SearchIndexingControllerTest {

    private static final String ORIGIN = "https://gameometro.com";
    private static final String FIRST = "3000000a-0000-4000-8000-00000000000b";
    private static final String SECOND = "30000000-0000-4000-8000-000000000003";

    private final FakeGames games = new FakeGames();

    @Test
    void anIndexableDeploymentSteersCrawlersAwayFromPersonalAndQueryPagesAndNamesItsSitemap()
            throws Exception {
        var body =
                mvc(ORIGIN)
                        .perform(get("/robots.txt"))
                        .andExpect(status().isOk())
                        .andExpect(content().contentType("text/plain;charset=UTF-8"))
                        .andExpect(header().string("Cache-Control", "max-age=3600, public"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body)
                .startsWith("User-agent: *\n")
                .doesNotContain("Disallow: /\n")
                .contains(
                        "Disallow: /search\n",
                        "Disallow: /auth/\n",
                        "Disallow: /api/v1/me/\n",
                        "Disallow: /api/v1/admin/\n")
                .endsWith("\nSitemap: https://gameometro.com/sitemap.xml\n")
                // The public catalogue API stays crawlable: a rendering crawler needs it for page
                // content.
                .doesNotContain("Disallow: /api/\n", "Disallow: /api/v1/\n")
                // Personal pages stay fetchable so their noindex is read; a block would hide it.
                .doesNotContain("/mis-puntuaciones", "/mi-perfil", "/administracion");
    }

    @Test
    void everyOtherDeploymentDisallowsEverythingAndServesNoSitemap() throws Exception {
        for (var blank : new String[] {"", "  "}) {
            var mvc = mvc(blank);
            mvc.perform(get("/robots.txt"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("User-agent: *\nDisallow: /\n"));
            for (var path :
                    List.of(
                            "/sitemap.xml",
                            "/sitemaps/pages.xml",
                            "/sitemaps/games/" + FIRST + ".xml")) {
                mvc.perform(get(path)).andExpect(status().isNotFound());
            }
        }
        assertThat(games.calls).isEmpty();
    }

    @Test
    void refusesAnOriginThatIsNotABareHttpsOrigin() {
        for (var value :
                List.of(
                        "http://gameometro.com",
                        "https://gameometro.com/",
                        "https://gameometro.com/es",
                        "https://gameometro.com:8443",
                        "https://GAMEOMETRO.com",
                        "https://gameometro",
                        "https://-gameometro.com",
                        "https://gameometro-.com",
                        "https://gameometro..com",
                        "https://gameometro.c0m",
                        "gameometro.com")) {
            assertThatIllegalStateException()
                    .isThrownBy(() -> new SearchIndexingController(value, games))
                    .withMessageContaining("api.search-indexing.public-origin");
        }
    }

    @Test
    void theIndexListsThePagesSitemapAndOneBoundedSitemapPerGamePartition() throws Exception {
        games.starts = List.of(FIRST, SECOND);

        var body =
                mvc(ORIGIN)
                        .perform(get("/sitemap.xml"))
                        .andExpect(status().isOk())
                        .andExpect(content().contentType("application/xml;charset=UTF-8"))
                        .andExpect(header().string("Cache-Control", "max-age=3600, public"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(locations(body, "sitemapindex"))
                .containsExactly(
                        ORIGIN + "/sitemaps/pages.xml",
                        ORIGIN + "/sitemaps/games/" + FIRST + ".xml",
                        ORIGIN + "/sitemaps/games/" + SECOND + ".xml");
        assertThat(games.calls).containsExactly("starts 10000");
    }

    @Test
    void aGamesSitemapListsTheCanonicalPageOfEveryGameInItsPartition() throws Exception {
        games.partition =
                List.of(
                        new IndexableGame(FIRST, "death-stranding-" + FIRST),
                        new IndexableGame(SECOND, "hollow-knight"));

        var body =
                mvc(ORIGIN)
                        .perform(get("/sitemaps/games/" + FIRST + ".xml"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(locations(body, "urlset"))
                .containsExactly(
                        ORIGIN + "/games/" + FIRST + "/death-stranding-" + FIRST,
                        ORIGIN + "/games/" + SECOND + "/hollow-knight");
        assertThat(games.calls).containsExactly("partition " + FIRST + "/10000");
    }

    @Test
    void aPartitionPastEveryGameIsGoneAndANonCanonicalStartIsNotARoute() throws Exception {
        var mvc = mvc(ORIGIN);
        mvc.perform(get("/sitemaps/games/" + FIRST + ".xml")).andExpect(status().isNotFound());
        mvc.perform(get("/sitemaps/games/" + FIRST.toUpperCase() + ".xml"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/sitemaps/games/death-stranding.xml")).andExpect(status().isNotFound());
        assertThat(games.calls).containsExactly("partition " + FIRST + "/10000");
    }

    @Test
    void thePagesSitemapListsOnlyFixedPublicRoutesTheApplicationServes() throws Exception {
        var body =
                mvc(ORIGIN)
                        .perform(get("/sitemaps/pages.xml"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(locations(body, "urlset"))
                .containsExactlyElementsOf(
                        SearchIndexingController.PAGE_PATHS.stream().map(p -> ORIGIN + p).toList());
        assertThat(SearchIndexingController.PAGE_PATHS)
                // FrontendRouteControllerTest proves each one is served with its own metadata.
                .contains("/", "/lanzamientos", "/topometro", "/privacidad")
                .noneMatch(path -> path.contains("?") || path.contains("{"))
                .noneMatch(
                        path ->
                                SearchIndexingController.DISALLOWED_PATHS.stream()
                                        .anyMatch(path::startsWith));
        assertThat(games.calls).isEmpty();
    }

    private MockMvc mvc(String origin) {
        return MockMvcBuilders.standaloneSetup(new SearchIndexingController(origin, games)).build();
    }

    /** The fixed paths the SPA entry-point controller serves. */
    private static List<String> locations(String xml, String root) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var document =
                factory.newDocumentBuilder()
                        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertThat(document.getDocumentElement().getLocalName()).isEqualTo(root);
        assertThat(document.getDocumentElement().getNamespaceURI())
                .isEqualTo("http://www.sitemaps.org/schemas/sitemap/0.9");
        var nodes = document.getElementsByTagNameNS("*", "loc");
        List<String> values = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            values.add(nodes.item(i).getTextContent());
        }
        return values;
    }

    private static final class FakeGames implements ListIndexableGamesUseCase {
        private final List<String> calls = new ArrayList<>();
        private List<String> starts = List.of();
        private List<IndexableGame> partition = List.of();

        @Override
        public List<String> partitionStarts(int partitionSize) {
            calls.add("starts " + partitionSize);
            return starts;
        }

        @Override
        public List<IndexableGame> partition(String startGameId, int partitionSize) {
            calls.add("partition " + startGameId + "/" + partitionSize);
            return partition;
        }
    }
}
