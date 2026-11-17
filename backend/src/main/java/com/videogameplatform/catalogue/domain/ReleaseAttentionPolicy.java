package com.videogameplatform.catalogue.domain;

/**
 * Release attention, version {@value #VERSION} (ATT-001 to ATT-007, ADR-0041): how much attention
 * a Game draws among the candidates of one date range.
 *
 * <p>Two blocks weigh the same. The provider block is the mean of three signals, each on a log
 * scale relative to the strongest candidate of the range: Hypes, Want to Play and Visits. A derived
 * product (remake, remaster, port, expanded game) uses the greater of its own signal and a {@link
 * #INHERITED_SHARE share} of its parent product's; a Game first released before the range is a
 * re-release and counts only a {@link #RE_RELEASE_SHARE share} of its own signals.
 *
 * <p>Only a launch draws attention (ATT-007): a release whose stage is {@link #LAUNCH_STAGES a full
 * release or unknown}. Early access, advance access, betas and alphas never give a Game attention,
 * and a Game is a re-release when one of its launch releases, not cancelled or delayed, ended
 * before the range. The editorial
 * block is the 1–100 score of the Game in the operator-loaded editorial list covering the range,
 * or nothing. A missing signal adds nothing and is never stored as zero; a Game without any signal
 * and without an editorial score has no attention and follows every Game with one.
 *
 * <p>The ranking runs in PostgreSQL over the range's candidates; this type names its constants so
 * that every reader shares one definition. The weights and shares below are the approved defaults;
 * the catalogue operator may tune them as product settings ({@link AttentionWeights}, #450)
 * without changing this shape.
 */
public final class ReleaseAttentionPolicy {

    /** The version a change of signal or scale must replace; tuned weights keep it. */
    public static final String VERSION = "attention-v1";

    /** Want to Play and Visits are provider shares of a total; the scale reads them per million. */
    public static final double SHARE_RESOLUTION = 1_000_000d;

    /** The part of a parent product's signal a derived product may claim as its own. */
    public static final double INHERITED_SHARE = 0.5d;

    /** The part of its own signals a Game first released before the range keeps. */
    public static final double RE_RELEASE_SHARE = 0.5d;

    /** The weight of the editorial block; the provider block takes the rest. */
    public static final double EDITORIAL_WEIGHT = 0.5d;

    /**
     * The release stages that count as a launch: a full release, and a release whose stage the
     * provider never stated, which is almost always an ordinary launch.
     */
    public static final java.util.List<ReleaseStage> LAUNCH_STAGES =
            java.util.List.of(ReleaseStage.FULL_RELEASE, ReleaseStage.UNKNOWN);

    /** Attention is compared at this many decimals of its 0–100 scale. */
    public static final int SCALE = 4;

    private ReleaseAttentionPolicy() {}
}
