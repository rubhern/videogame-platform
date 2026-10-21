package com.videogameplatform.ratings.adapter.persistence;

/**
 * SQL for a mean of rating contributions. A platform-derived contribution is a quotient stored to
 * twenty decimal places, so two equal means reached through different quotients can differ in the
 * last place. Rounding to eighteen places first makes equal means compare equal, for ordering and
 * ties, while distinct means stay apart; only the exposed form then rounds half up to one decimal
 * (AGG-005).
 */
final class ContributionMean {
    private ContributionMean() {}

    /** The unrounded mean, for ordering and comparison. */
    static String exact(String contribution) {
        return "round(avg(" + contribution + "), 18)";
    }

    /** The mean as exposed: half up to one decimal. */
    static String exposed(String contribution) {
        return "round(" + exact(contribution) + ", 1)";
    }

    /** The integer distribution bucket of one contribution: half up to the nearest integer. */
    static String bucket(String contribution) {
        return "round(" + contribution + ")::integer";
    }
}
