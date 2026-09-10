package com.videogameplatform.identity.configuration;

import com.videogameplatform.identity.adapter.session.RatingReturnContext;
import com.videogameplatform.identity.adapter.session.RatingReturnContextStore;
import com.videogameplatform.identity.adapter.web.RatingBoundary;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * Produces a safe outcome when authentication is cancelled or fails.
 *
 * <p>It discards any pending return context so a failed attempt can never be resumed. When a rating
 * boundary started the attempt, the visitor returns to the same game with a safe cancelled outcome
 * and no selection; otherwise the flow lands on the home page. No rating command is executed.
 */
@Component
public final class RatingIntentAuthenticationFailureHandler
        extends SimpleUrlAuthenticationFailureHandler {

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
}
