package com.videogameplatform.api.delivery.frontend;

import com.videogameplatform.api.delivery.frontend.PageMetadataResolver.PlatformPage;
import com.videogameplatform.api.delivery.frontend.PageMetadataResolver.PublicPage;
import com.videogameplatform.catalogue.application.sharing.GetSharePreviewUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Serves the packaged SPA entry point for the browser routes owned by React Router, with the
 * route's page metadata in its head (ADR-0049), so link previews work without JavaScript.
 */
@Controller
@ConditionalOnResource(resources = "classpath:/static/index.html")
class FrontendRouteController {
    private static final MediaType HTML =
            new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private final FrontendDocument document;
    private final PageMetadataResolver pages;

    @Autowired
    FrontendRouteController(GetSharePreviewUseCase previews) {
        this(packagedDocument(), new PageMetadataResolver(previews));
    }

    FrontendRouteController(FrontendDocument document, PageMetadataResolver pages) {
        this.document = Objects.requireNonNull(document, "document");
        this.pages = Objects.requireNonNull(pages, "pages");
    }

    @GetMapping("/")
    ResponseEntity<String> home(HttpServletRequest request) {
        return serve(pages.publicPage(PublicPage.HOME), request);
    }

    @GetMapping("/topometro")
    ResponseEntity<String> topometro(HttpServletRequest request) {
        return serve(pages.publicPage(PublicPage.TOPOMETRO), request);
    }

    @GetMapping("/lanzamientos")
    ResponseEntity<String> releases(HttpServletRequest request) {
        return serve(pages.publicPage(PublicPage.RELEASES), request);
    }

    @GetMapping("/lanzamientos/destacados")
    ResponseEntity<String> featured(HttpServletRequest request) {
        return serve(pages.publicPage(PublicPage.FEATURED), request);
    }

    @GetMapping("/lanzamientos/calendario")
    ResponseEntity<String> calendar(HttpServletRequest request) {
        return serve(pages.publicPage(PublicPage.CALENDAR), request);
    }

    @GetMapping("/plataformas")
    ResponseEntity<String> platforms(HttpServletRequest request) {
        return serve(pages.publicPage(PublicPage.PLATFORMS), request);
    }

    /** The information and legal pages: their own title, description and canonical path. */
    @GetMapping("/{page:sobre-gameometro|contacto|privacidad|condiciones|aviso-legal|cookies}")
    ResponseEntity<String> informationPage(@PathVariable String page, HttpServletRequest request) {
        PublicPage publicPage =
                PublicPage.ofPath("/" + page).orElseThrow(() -> new IllegalStateException(page));
        return serve(pages.publicPage(publicPage), request);
    }

    /** Personal, administration and search routes: generic tags, never indexed (#537). */
    @GetMapping({
        "/search",
        "/mis-puntuaciones",
        "/mis-puntuaciones/estadisticas",
        "/mis-plataformas",
        "/mis-deseados",
        "/mi-perfil",
        "/mi-perfil/borrado",
        "/bienvenida",
        "/administracion/catalogo"
    })
    ResponseEntity<String> unlistedPage(HttpServletRequest request) {
        return serve(PageMetadata.unlisted(), request);
    }

    /**
     * A platform's catalogue (#389), TOPómetro (#387) or upcoming highlights (#388): a featured
     * family's key or a platform's own identifier.
     */
    @GetMapping(
            "/plataformas/{platformKey:[a-z0-9]+(?:-[a-z0-9]+)*}/{page:catalogo|topometro|proximos}")
    ResponseEntity<String> platformPage(
            @PathVariable String platformKey,
            @PathVariable String page,
            HttpServletRequest request) {
        Objects.requireNonNull(platformKey, "platformKey");
        PlatformPage platformPage =
                PlatformPage.of(page).orElseThrow(() -> new IllegalStateException(page));
        return serve(pages.platform(platformPage, platformKey), request);
    }

    /** {@code /games/{gameId}}: the router's optional slug is absent. */
    @GetMapping("/games/{gameId:[a-z0-9]+(?:-[a-z0-9]+)*}")
    ResponseEntity<String> game(@PathVariable String gameId, HttpServletRequest request) {
        Objects.requireNonNull(gameId, "gameId");
        return serve(pages.game(gameId), request);
    }

    /** The slug is presentational: the canonical URL always carries the game's current slug. */
    @GetMapping("/games/{gameId:[a-z0-9-]+}/{slug:[a-z0-9]+(?:-[a-z0-9]+)*}")
    ResponseEntity<String> gameDetails(
            @PathVariable String gameId, @PathVariable String slug, HttpServletRequest request) {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(slug, "slug");
        return serve(pages.game(gameId), request);
    }

    /**
     * The same document for every client, never per user. {@code public, no-cache} with a strong
     * validator lets any cache keep it while every use revalidates, so a release or catalogue
     * change is seen at once and the hashed assets it names always exist.
     */
    private ResponseEntity<String> serve(PageMetadata page, HttpServletRequest request) {
        String body = document.render(page, origin(request));
        String etag = "\"" + digest(body) + "\"";
        CacheControl cacheControl = CacheControl.noCache().cachePublic();
        if (new ServletWebRequest(request).checkNotModified(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(cacheControl)
                    .build();
        }
        return ResponseEntity.status(page.missing() ? HttpStatus.NOT_FOUND : HttpStatus.OK)
                .contentType(HTML)
                .eTag(etag)
                .cacheControl(cacheControl)
                .body(body);
    }

    /** The public origin as the edge forwarded it, the same source as the OIDC {@code baseUrl}. */
    private static String origin(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromContextPath(request)
                .replacePath(null)
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    private static String digest(String body) {
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static FrontendDocument packagedDocument() {
        try (var input = new ClassPathResource("static/index.html").getInputStream()) {
            return FrontendDocument.of(input.readAllBytes());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
