package com.videogameplatform.identity.configuration;

import com.videogameplatform.identity.adapter.session.RatingReturnContext;
import com.videogameplatform.identity.adapter.session.RatingReturnContextStore;
import com.videogameplatform.identity.adapter.session.ResumedRatingIntent;
import com.videogameplatform.identity.adapter.web.ProductIdentity;
import com.videogameplatform.identity.adapter.web.RatingBoundary;
import com.videogameplatform.identity.domain.UserId;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Resumes the rating journey after a successful authentication callback.
 *
 * <p>It atomically consumes the return context the boundary stored, so callback reload or replay
 * cannot resume the same logical selection twice. A valid, unexpired context becomes a single-use
 * recovered selection bound to the authenticated {@link UserId} and the browser returns to the same
 * game. An expired context returns to the game with a safe expiry outcome and no selection. A plain
 * login with no pending context lands on the home page.
 */
@Component
public final class RatingResumeAuthenticationSuccessHandler
        implements AuthenticationSuccessHandler {

    private final RatingReturnContextStore store;
    private final SimpleUrlAuthenticationSuccessHandler redirect =
            new SimpleUrlAuthenticationSuccessHandler();

    RatingResumeAuthenticationSuccessHandler(RatingReturnContextStore store) {
        this.store = store;
        // Targets are always server-built local paths, never client input, so the default
        // redirect strategy is safe. Ignore any saved request and use the resolved target.
        this.redirect.setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        Optional<RatingReturnContext> context = store.takeReturnContext();
        if (context.isEmpty()) {
            redirect.setDefaultTargetUrl(RatingBoundary.home());
            redirect.onAuthenticationSuccess(request, response, authentication);
            return;
        }

        RatingReturnContext pending = context.get();
        if (store.isExpired(pending)) {
            sendTo(
                    request,
                    response,
                    authentication,
                    RatingBoundary.gamePathWithOutcome(
                            pending.gameId(), pending.slug(), RatingBoundary.Outcome.EXPIRED));
            return;
        }

        UserId userId =
                ProductIdentity.resolve(authentication)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Successful authentication without a resolvable"
                                                        + " product identity"));
        store.saveResumedIntent(
                new ResumedRatingIntent(
                        pending.gameId(), pending.slug(), pending.value(), userId.value()));
        sendTo(
                request,
                response,
                authentication,
                RatingBoundary.gamePathWithOutcome(
                        pending.gameId(), pending.slug(), RatingBoundary.Outcome.RESUMED));
    }

    private void sendTo(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication,
            String target)
            throws IOException, ServletException {
        redirect.setDefaultTargetUrl(target);
        redirect.onAuthenticationSuccess(request, response, authentication);
    }
}
