package com.videogameplatform.api.delivery.frontend;

import com.videogameplatform.catalogue.application.details.IndexableGame;
import com.videogameplatform.catalogue.application.details.ListIndexableGamesUseCase;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * {@code robots.txt} and the sitemap index for search engines (#537).
 *
 * <p>Only a deployment with a configured public origin is indexable; any other one disallows every
 * path and serves no sitemap. Robots directives steer crawlers and are never access control.
 * Games are listed in bounded keyset partitions of the current publication, so neither the index
 * nor a partition grows per-request memory with the catalogue.
 */
@Controller
class SearchIndexingController {

    /** Games per child sitemap: well under the protocol's 50,000 URLs and 50 MB. */
    static final int GAMES_PER_SITEMAP = 10_000;

    /**
     * The fixed public pages {@link PageMetadataResolver.PublicPage} describes, each at its
     * canonical, parameter-free path. A platform's own pages are reached through {@code
     * /plataformas}.
     */
    static final List<String> PAGE_PATHS =
            Arrays.stream(PageMetadataResolver.PublicPage.values())
                    .map(PageMetadataResolver.PublicPage::path)
                    .toList();

    /**
     * Paths crawlers must not fetch: personal and administration APIs, authentication flows, and
     * the unbounded search space. Personal and administration pages are deliberately absent: they
     * stay crawlable so crawlers read their {@code noindex}, which a blocked fetch would hide.
     */
    static final List<String> DISALLOWED_PATHS =
            List.of("/api/v1/me/", "/api/v1/admin/", "/auth/", "/login", "/search");

    private static final MediaType TEXT = MediaType.parseMediaType("text/plain;charset=UTF-8");
    private static final MediaType XML = MediaType.parseMediaType("application/xml;charset=UTF-8");
    private static final CacheControl CACHE_CONTROL =
            CacheControl.maxAge(Duration.ofHours(1)).cachePublic();
    private static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    private static final String NAMESPACE = "http://www.sitemaps.org/schemas/sitemap/0.9";

    private final SearchIndexingOrigin origin;
    private final ListIndexableGamesUseCase games;

    SearchIndexingController(
            @Value("${" + SearchIndexingOrigin.PROPERTY + ":}") String publicOrigin,
            ListIndexableGamesUseCase games) {
        this.origin = SearchIndexingOrigin.parse(publicOrigin);
        this.games = games;
    }

    @GetMapping("/robots.txt")
    @ResponseBody
    ResponseEntity<String> robots() {
        var body = new StringBuilder("User-agent: *\n");
        if (!origin.indexable()) {
            body.append("Disallow: /\n");
        } else {
            DISALLOWED_PATHS.forEach(path -> body.append("Disallow: ").append(path).append('\n'));
            body.append("\nSitemap: ").append(origin.url("/sitemap.xml")).append('\n');
        }
        return ResponseEntity.ok()
                .contentType(TEXT)
                .cacheControl(CACHE_CONTROL)
                .body(body.toString());
    }

    @GetMapping("/sitemap.xml")
    @ResponseBody
    ResponseEntity<String> sitemapIndex() {
        if (!origin.indexable()) {
            return ResponseEntity.notFound().build();
        }
        var body =
                new StringBuilder(XML_DECLARATION)
                        .append("<sitemapindex xmlns=\"" + NAMESPACE + "\">\n");
        appendSitemap(body, "/sitemaps/pages.xml");
        for (var start : games.partitionStarts(GAMES_PER_SITEMAP)) {
            appendSitemap(body, "/sitemaps/games/" + start + ".xml");
        }
        return xml(body.append("</sitemapindex>\n"));
    }

    @GetMapping("/sitemaps/pages.xml")
    @ResponseBody
    ResponseEntity<String> pagesSitemap() {
        if (!origin.indexable()) {
            return ResponseEntity.notFound().build();
        }
        var body = urlSet();
        PAGE_PATHS.forEach(path -> appendUrl(body, path));
        return xml(body.append("</urlset>\n"));
    }

    /**
     * One partition from its published start. A start that no longer begins a partition still
     * lists its bounded range; one past every game is gone, so crawlers drop it.
     */
    @GetMapping(
            "/sitemaps/games/{start:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}}.xml")
    @ResponseBody
    ResponseEntity<String> gamesSitemap(@PathVariable String start) {
        if (!origin.indexable()) {
            return ResponseEntity.notFound().build();
        }
        List<IndexableGame> partition = games.partition(start, GAMES_PER_SITEMAP);
        if (partition.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var body = urlSet();
        partition.forEach(game -> appendUrl(body, gamePath(game)));
        return xml(body.append("</urlset>\n"));
    }

    /** The canonical game page every internal link uses. */
    static String gamePath(IndexableGame game) {
        return "/games/" + game.gameId() + "/" + game.slug();
    }

    private StringBuilder urlSet() {
        return new StringBuilder(XML_DECLARATION).append("<urlset xmlns=\"" + NAMESPACE + "\">\n");
    }

    private void appendSitemap(StringBuilder body, String path) {
        body.append("  <sitemap><loc>")
                .append(escape(origin.url(path)))
                .append("</loc></sitemap>\n");
    }

    private void appendUrl(StringBuilder body, String path) {
        body.append("  <url><loc>").append(escape(origin.url(path))).append("</loc></url>\n");
    }

    private static ResponseEntity<String> xml(StringBuilder body) {
        return ResponseEntity.ok()
                .contentType(XML)
                .cacheControl(CACHE_CONTROL)
                .body(body.toString());
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
