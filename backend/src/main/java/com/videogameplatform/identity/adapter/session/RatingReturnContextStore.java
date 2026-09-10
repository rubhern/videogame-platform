package com.videogameplatform.identity.adapter.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Server-side, single-use store for the rating return context and the recovered selection.
 *
 * <p>State lives exclusively in the application session, which the browser addresses only through
 * an opaque {@code HttpOnly} cookie, so it is tamper-resistant and never leaves the server. The
 * return context is written when a visitor starts authentication from the rating boundary and taken
 * exactly once when the authentication callback resolves. The recovered selection is written once
 * and taken once by the game page, so a reload or replay cannot resume it twice.
 */
@Component
public class RatingReturnContextStore {

    private static final String RETURN_CONTEXT_ATTRIBUTE =
            RatingReturnContextStore.class.getName() + ".returnContext";
    private static final String RESUMED_INTENT_ATTRIBUTE =
            RatingReturnContextStore.class.getName() + ".resumedIntent";

    private final HttpServletRequest request;
    private final Clock clock;
    private final Duration timeToLive;

    public RatingReturnContextStore(
            HttpServletRequest request,
            Clock clock,
            @Value("${identity.rating-return-context.ttl:PT10M}") Duration timeToLive) {
        this.request = request;
        this.clock = clock;
        this.timeToLive = timeToLive;
    }

    /** Persists the return context, replacing any earlier pending context in this session. */
    public void saveReturnContext(RatingReturnContext context) {
        request.getSession(true).setAttribute(RETURN_CONTEXT_ATTRIBUTE, context);
    }

    /** Atomically removes and returns the pending return context, if one exists. */
    public Optional<RatingReturnContext> takeReturnContext() {
        return takeAttribute(RETURN_CONTEXT_ATTRIBUTE, RatingReturnContext.class);
    }

    /** A return context is stale once it has outlived its configured time to live. */
    public boolean isExpired(RatingReturnContext context) {
        return context.issuedAt().plus(timeToLive).isBefore(clock.instant());
    }

    /** Persists the recovered selection produced by a single valid consumption. */
    public void saveResumedIntent(ResumedRatingIntent intent) {
        request.getSession(true).setAttribute(RESUMED_INTENT_ATTRIBUTE, intent);
    }

    /** Atomically removes and returns the recovered selection, enforcing single-use reads. */
    public Optional<ResumedRatingIntent> takeResumedIntent() {
        return takeAttribute(RESUMED_INTENT_ATTRIBUTE, ResumedRatingIntent.class);
    }

    private <T> Optional<T> takeAttribute(String attribute, Class<T> type) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        Object value = session.getAttribute(attribute);
        if (!type.isInstance(value)) {
            return Optional.empty();
        }
        session.removeAttribute(attribute);
        return Optional.of(type.cast(value));
    }
}
