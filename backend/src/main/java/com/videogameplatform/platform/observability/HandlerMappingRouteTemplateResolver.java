package com.videogameplatform.platform.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Looks up the controller mapping a request would have reached.
 *
 * <p>Only a registered pattern can be returned, so the vocabulary stays bounded by the controllers.
 * The lookup runs after the response is complete and only for requests without a matched route,
 * such as those a security filter rejected; any failure degrades to no template.
 */
final class HandlerMappingRouteTemplateResolver implements RouteTemplateResolver {

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappings;

    HandlerMappingRouteTemplateResolver(
            ObjectProvider<RequestMappingHandlerMapping> handlerMappings) {
        this.handlerMappings = handlerMappings;
    }

    @Override
    public Optional<String> resolve(HttpServletRequest request) {
        RequestMappingHandlerMapping mapping = handlerMappings.getIfAvailable();
        if (mapping == null) {
            return Optional.empty();
        }
        boolean parsedHere = !ServletRequestPathUtils.hasParsedRequestPath(request);
        try {
            if (parsedHere) {
                ServletRequestPathUtils.parseAndCache(request);
            }
            if (mapping.getHandler(request) == null) {
                return Optional.empty();
            }
            return request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
                            instanceof String pattern
                    ? Optional.of(pattern)
                    : Optional.empty();
        } catch (Exception _) {
            // A method or media-type mismatch has no single template; the event stays UNMATCHED.
            return Optional.empty();
        } finally {
            if (parsedHere) {
                ServletRequestPathUtils.clearParsedRequestPath(request);
            }
        }
    }
}
