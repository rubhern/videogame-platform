package com.videogameplatform.identity.configuration;

import com.videogameplatform.identity.adapter.web.ProductIdentity;
import com.videogameplatform.identity.application.AccountDeletionStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** A restarted BFF must never admit an identity whose erasure is pending or complete. */
final class AccountDeletionFenceFilter extends OncePerRequestFilter {
    private final ObjectProvider<AccountDeletionStore> stores;

    AccountDeletionFenceFilter(ObjectProvider<AccountDeletionStore> stores) {
        this.stores = stores;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var identity =
                ProductIdentity.resolve(SecurityContextHolder.getContext().getAuthentication());
        if (identity.isPresent() && stores.getIfAvailable() != null) {
            boolean blocked;
            try {
                blocked = stores.getObject().blocked(identity.get().value());
            } catch (RuntimeException unavailable) {
                String correlation = response.getHeader("X-Correlation-ID");
                if (correlation == null) correlation = java.util.UUID.randomUUID().toString();
                response.setStatus(503);
                response.setHeader("Cache-Control", "no-store");
                response.setHeader("X-Correlation-ID", correlation);
                response.setContentType("application/problem+json");
                response.getWriter()
                        .write(
                                "{\"type\":\"urn:videogame-platform:problem:service-overloaded\",\"title\":\"Account access unavailable\",\"status\":503,\"detail\":\"Retry later.\",\"instance\":\"urn:videogame-platform:problem-instance:"
                                        + correlation
                                        + "\",\"code\":\"SERVICE_OVERLOADED\",\"category\":\"technical\",\"correlationId\":\""
                                        + correlation
                                        + "\"}");
                return;
            }
            if (blocked) {
                if (request.getSession(false) != null) request.getSession(false).invalidate();
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
