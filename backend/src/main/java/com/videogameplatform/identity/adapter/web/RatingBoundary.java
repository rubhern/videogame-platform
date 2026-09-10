package com.videogameplatform.identity.adapter.web;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Validation and allowlisted redirect construction for the rating authentication boundary.
 *
 * <p>Every browser-supplied value is untrusted. The game identifier and slug are constrained to the
 * exact character set the SPA routes accept, so a resolved destination is always a local game page
 * and can never become an open redirect. The rating value is validated against the approved 1-10
 * range before any authentication starts.
 */
public final class RatingBoundary {

    /** The safe post-authentication outcome carried back to the game page as a query marker. */
    public enum Outcome {
        RESUMED,
        EXPIRED,
        CANCELLED,
        INVALID;

        String marker() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    static final int MIN_VALUE = 1;
    static final int MAX_VALUE = 10;

    private static final Pattern GAME_ID = Pattern.compile("[a-z0-9-]{1,100}");
    // Possessive quantifiers keep matching linear and stack-safe for hostile long input.
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]++(?:-[a-z0-9]++)*+");
    private static final String HOME = "/";
    private static final String INTENT_QUERY = "rating-intent";

    private RatingBoundary() {}

    public static boolean isValidGameId(String gameId) {
        return gameId != null && GAME_ID.matcher(gameId).matches();
    }

    public static Optional<String> validSlug(String slug) {
        return slug != null && SLUG.matcher(slug).matches() ? Optional.of(slug) : Optional.empty();
    }

    public static Optional<Integer> validValue(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return Optional.empty();
        }
        try {
            int value = Integer.parseInt(rawValue.trim());
            return value >= MIN_VALUE && value <= MAX_VALUE ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    /** Builds the allowlisted game page path from a validated game identifier and optional slug. */
    public static String gamePath(String gameId, String slug) {
        StringBuilder path = new StringBuilder("/games/").append(gameId);
        validSlug(slug).ifPresent(value -> path.append('/').append(value));
        return path.toString();
    }

    /** The game page path carrying the safe post-authentication outcome marker. */
    public static String gamePathWithOutcome(String gameId, String slug, Outcome outcome) {
        return gamePath(gameId, slug) + "?" + INTENT_QUERY + "=" + outcome.marker();
    }

    /** The safe fallback destination when no local game page can be resolved. */
    public static String home() {
        return HOME;
    }
}
