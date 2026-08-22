package com.videogameplatform.identity.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Adds browser origin and fetch-metadata checks to the CSRF-protected session mutation. */
final class SameOriginStateChangeFilter extends OncePerRequestFilter {

    private static final String FETCH_SITE_HEADER = "Sec-Fetch-Site";

    private final CsrfProblemAccessDeniedHandler accessDeniedHandler;

    SameOriginStateChangeFilter(CsrfProblemAccessDeniedHandler accessDeniedHandler) {
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
                || !(request.getContextPath() + IdentitySecurityConfiguration.SESSION_PATH)
                        .equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!hasTrustedOrigin(request) || !hasTrustedFetchMetadata(request)) {
            accessDeniedHandler.handle(
                    request, response, new CsrfException("Cross-origin state change rejected"));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean hasTrustedOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null) {
            return true;
        }
        try {
            URI value = new URI(origin);
            return value.getUserInfo() == null
                    && value.getPath().isEmpty()
                    && value.getQuery() == null
                    && value.getFragment() == null
                    && request.getScheme().equalsIgnoreCase(value.getScheme())
                    && request.getServerName().equalsIgnoreCase(value.getHost())
                    && effectivePort(request.getScheme(), request.getServerPort())
                            == effectivePort(value.getScheme(), value.getPort());
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static boolean hasTrustedFetchMetadata(HttpServletRequest request) {
        String fetchSite = request.getHeader(FETCH_SITE_HEADER);
        return fetchSite == null || "same-origin".equals(fetchSite.toLowerCase(Locale.ROOT));
    }

    private static int effectivePort(String scheme, int port) {
        if (port >= 0) {
            return port;
        }
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }
}
