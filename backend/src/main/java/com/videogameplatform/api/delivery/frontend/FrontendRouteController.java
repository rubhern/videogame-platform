package com.videogameplatform.api.delivery.frontend;

import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Serves the packaged SPA entry point for the browser routes owned by React Router. */
@Controller
@ConditionalOnResource(resources = "classpath:/static/index.html")
class FrontendRouteController {

    @GetMapping("/")
    String frontendEntryPoint() {
        return "forward:/index.html";
    }

    @GetMapping({
        "/lanzamientos",
        "/lanzamientos/destacados",
        "/lanzamientos/calendario",
        "/topometro",
        "/plataformas",
        "/search",
        "/mis-puntuaciones",
        "/mis-puntuaciones/estadisticas",
        "/mis-plataformas",
        "/mi-perfil",
        "/mi-perfil/borrado",
        "/bienvenida",
        "/administracion/catalogo",
        "/privacidad",
        "/condiciones",
        "/aviso-legal",
        "/cookies"
    })
    String searchFrontendEntryPoint() {
        return frontendEntryPoint();
    }

    /**
     * A platform's catalogue (#389), TOPómetro (#387) or upcoming highlights (#388): a featured
     * family's key or a platform's own identifier.
     */
    @GetMapping({
        "/plataformas/{platformKey:[a-z0-9]+(?:-[a-z0-9]+)*}/catalogo",
        "/plataformas/{platformKey:[a-z0-9]+(?:-[a-z0-9]+)*}/topometro",
        "/plataformas/{platformKey:[a-z0-9]+(?:-[a-z0-9]+)*}/proximos"
    })
    String platformCatalogueFrontendEntryPoint(@PathVariable String platformKey) {
        Objects.requireNonNull(platformKey, "platformKey");
        return frontendEntryPoint();
    }

    @GetMapping("/games/{slug:[a-z0-9]+(?:-[a-z0-9]+)*}")
    String gameFrontendEntryPoint(@PathVariable String slug) {
        Objects.requireNonNull(slug, "slug");
        return frontendEntryPoint();
    }

    @GetMapping("/games/{gameId:[a-z0-9-]+}/{slug:[a-z0-9]+(?:-[a-z0-9]+)*}")
    String gameDetailsFrontendEntryPoint(@PathVariable String gameId, @PathVariable String slug) {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(slug, "slug");
        return frontendEntryPoint();
    }
}
