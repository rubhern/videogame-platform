package com.videogameplatform.api.delivery;

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

    @GetMapping("/games/{slug:[a-z0-9]+(?:-[a-z0-9]+)*}")
    String gameFrontendEntryPoint(@PathVariable String slug) {
        Objects.requireNonNull(slug, "slug");
        return frontendEntryPoint();
    }
}
