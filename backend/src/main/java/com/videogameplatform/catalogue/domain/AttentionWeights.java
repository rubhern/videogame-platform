package com.videogameplatform.catalogue.domain;

/**
 * The operator-tunable weights of release attention (ATT-001 to ATT-007, ADR-0041), applied to the
 * fixed shape {@link ReleaseAttentionPolicy} names.
 *
 * <p>The provider block is the weighted mean of the three provider signals, each already on its
 * log scale relative to the strongest candidate: {@code hypes}, {@code wantToPlay} and {@code
 * visits} weigh against each other, so only their proportions matter, and at least one must be
 * positive. {@code editorial} is the share of the 0–100 attention the editorial block takes; the
 * provider block takes the rest. {@code inheritedShare} is the part of a parent product's signal a
 * derived product may claim, and {@code reReleaseShare} the part of its own signals a re-release
 * keeps.
 */
public record AttentionWeights(
        double hypes,
        double wantToPlay,
        double visits,
        double editorial,
        double inheritedShare,
        double reReleaseShare) {

    /** The approved {@code attention-v1} weights: three equal signals and an even split. */
    public static final AttentionWeights DEFAULT =
            new AttentionWeights(
                    1d,
                    1d,
                    1d,
                    ReleaseAttentionPolicy.EDITORIAL_WEIGHT,
                    ReleaseAttentionPolicy.INHERITED_SHARE,
                    ReleaseAttentionPolicy.RE_RELEASE_SHARE);

    public AttentionWeights {
        if (!nonNegative(hypes) || !nonNegative(wantToPlay) || !nonNegative(visits)) {
            throw new IllegalArgumentException("A provider signal weight cannot be negative");
        }
        if (hypes + wantToPlay + visits <= 0) {
            throw new IllegalArgumentException("At least one provider signal must weigh");
        }
        if (!share(editorial) || !share(inheritedShare) || !share(reReleaseShare)) {
            throw new IllegalArgumentException("A share lies between 0 and 1");
        }
    }

    private static boolean nonNegative(double value) {
        return Double.isFinite(value) && value >= 0;
    }

    private static boolean share(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }
}
