package com.videogameplatform.identity.configuration;

import com.videogameplatform.identity.adapter.session.RatingReturnContext;
import com.videogameplatform.identity.adapter.session.RatingReturnContextStore;
import com.videogameplatform.identity.adapter.web.RatingBoundary;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * Produces a safe outcome when authentication is cancelled or fails.
 *
 * <p>It discards any pending return context so a failed attempt can never be resumed. When a rating
 * boundary started the attempt, the visitor returns to the same game with a safe cancelled outcome
 * and no selection; otherwise the flow lands on the home page. No rating command is executed.
 *
 * <p>The request-completion event records whether the visitor cancelled or the login failed. A
 * failure is also logged with its OAuth 2.0 error code from a closed allowlist; the state,
 * authorization code, tokens and redirect URI are never read for diagnostics.
 */
@Component
public final class RatingIntentAuthenticationFailureHandler
        extends SimpleUrlAuthenticationFailureHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RatingIntentAuthenticationFailureHandler.class);
    private static final String ACCESS_DENIED = "access_denied";
    private static final Set<String> REPORTED_OAUTH2_ERRORS =
            Set.of(
                    ACCESS_DENIED,
                    "authorization_request_not_found",
                    "client_registration_not_found",
                    "invalid_client",
                    "invalid_grant",
                    "invalid_id_token",
                    "invalid_nonce",
                    "invalid_request",
                    "invalid_scope",
                    "invalid_state_parameter",
                    "invalid_token_response",
                    "invalid_user_info_response",
                    "server_error",
                    "temporarily_unavailable",
                    "unauthorized_client");

    private final RatingReturnContextStore store;

    RatingIntentAuthenticationFailureHandler(RatingReturnContextStore store) {
        super(RatingBoundary.home());
        this.store = store;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception)
            throws IOException, ServletException {
        String oauth2Error = oauth2Error(exception);
        boolean cancelled = ACCESS_DENIED.equals(oauth2Error);
        request.setAttribute(
                AuthenticationProblemEntryPoint.ERROR_CODE_ATTRIBUTE,
                cancelled ? "AUTHENTICATION_CANCELLED" : "AUTHENTICATION_FAILED");
        if (!cancelled) {
            LOGGER.atWarn()
                    .addKeyValue("error.code", "AUTHENTICATION_FAILED")
                    .addKeyValue("identity.oauth2_error", oauth2Error)
                    .log("OIDC login failed: oauth2_error={}", oauth2Error);
        }
        Optional<RatingReturnContext> context = store.takeReturnContext();
        if (context.isPresent()) {
            RatingReturnContext pending = context.get();
            getRedirectStrategy()
                    .sendRedirect(
                            request,
                            response,
                            RatingBoundary.gamePathWithOutcome(
                                    pending.gameId(),
                                    pending.slug(),
                                    RatingBoundary.Outcome.CANCELLED));
            return;
        }
        super.onAuthenticationFailure(request, response, exception);
    }

    private static String oauth2Error(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauth2
                && oauth2.getError() != null
                && REPORTED_OAUTH2_ERRORS.contains(oauth2.getError().getErrorCode())) {
            return oauth2.getError().getErrorCode();
        }
        return "other";
    }
}
