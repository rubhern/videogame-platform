package com.videogameplatform.api.delivery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the packaged SPA entry point for the browser routes owned by React Router. */
@Controller
@ConditionalOnResource(resources = "classpath:/static/index.html")
class FrontendRouteController {

    @GetMapping({"/", "/games/{slug:[a-z0-9]+(?:-[a-z0-9]+)*}"})
    String frontendEntryPoint() {
        return "forward:/index.html";
    }
}
