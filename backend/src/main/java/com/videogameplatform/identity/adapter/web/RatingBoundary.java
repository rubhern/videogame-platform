package com.videogameplatform.identity.adapter.web;

import com.videogameplatform.identity.adapter.session.RatingReturnContext;
import com.videogameplatform.identity.adapter.session.RatingReturnContext.PlatformScore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation and allowlisted redirect construction for the rating authentication boundary.
 *
 * <p>Every browser-supplied value is untrusted. The game identifier and slug are constrained to the
 * exact character set the SPA routes accept, so a resolved destination is always a local game page
 * and can never become an open redirect. The rating value is validated against the approved 1-10
 * range before any authentication starts. A list a rating may return to (#574) is one of a closed
 * set of local TOPómetro paths, revalidated whenever it becomes a redirect.
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

    private static final int MAX_SLUG_LENGTH = 200;
    private static final Pattern GAME_ID = Pattern.compile("[a-z0-9-]{1,100}");
    // Possessive quantifiers keep matching linear and stack-safe for hostile long input.
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]++(?:-[a-z0-9]++)*+");
    // The TOPómetro, global or on one platform, with at most its source: never any other path.
    private static final Pattern LIST_PATH =
            Pattern.compile(
                    "/(?:plataformas/[a-z0-9-]{1,100}/)?topometro(?:\\?source=(?:igdb|gameometro))?");
    private static final String HOME = "/";
    private static final String INTENT_QUERY = "rating-intent";

    private RatingBoundary() {}

    public static boolean isValidGameId(String gameId) {
        return gameId != null && GAME_ID.matcher(gameId).matches();
    }

    public static Optional<String> validSlug(String slug) {
        return slug != null && slug.length() <= MAX_SLUG_LENGTH && SLUG.matcher(slug).matches()
                ? Optional.of(slug)
                : Optional.empty();
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

    /** Validates the bounded set of played platforms and requires at least one score. */
    static Optional<List<PlatformScore>> validPlatforms(List<String> entries) {
        if (entries == null || entries.isEmpty() || entries.size() > 50) {
            return Optional.empty();
        }
        Set<String> ids = new HashSet<>();
        List<PlatformScore> selection = new ArrayList<>(entries.size());
        for (String entry : entries) {
            int separator = entry.lastIndexOf(':');
            if (separator < 1 || separator > 100 || entry.length() > separator + 3) {
                return Optional.empty();
            }
            String id = entry.substring(0, separator);
            if (id.isBlank() || !ids.add(id)) {
                return Optional.empty();
            }
            String rawScore = entry.substring(separator + 1);
            Optional<Integer> value = validValue(rawScore);
            if (!rawScore.isEmpty() && value.isEmpty()) {
                return Optional.empty();
            }
            selection.add(new PlatformScore(id, value.orElse(null)));
        }
        return selection.stream().anyMatch(platform -> platform.value() != null)
                ? Optional.of(selection)
                : Optional.empty();
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

    /** The list a rating started from, when it is one of the allowlisted local list paths. */
    public static Optional<String> validListPath(String path) {
        return path != null && path.length() <= 160 && LIST_PATH.matcher(path).matches()
                ? Optional.of(path)
                : Optional.empty();
    }

    /**
     * Where a rating journey ends with its outcome: the allowlisted list it started from, else the
     * game page.
     */
    public static String returnPathWithOutcome(RatingReturnContext context, Outcome outcome) {
        return validListPath(context.listPath())
                .map(
                        path ->
                                path
                                        + (path.contains("?") ? "&" : "?")
                                        + INTENT_QUERY
                                        + "="
                                        + outcome.marker())
                .orElseGet(() -> gamePathWithOutcome(context.gameId(), context.slug(), outcome));
    }

    /** The safe fallback destination when no local game page can be resolved. */
    public static String home() {
        return HOME;
    }
}
