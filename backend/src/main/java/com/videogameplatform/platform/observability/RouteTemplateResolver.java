package com.videogameplatform.platform.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** Resolves the registered route template of a request that MVC did not dispatch. */
@FunctionalInterface
interface RouteTemplateResolver {

    Optional<String> resolve(HttpServletRequest request);
}
