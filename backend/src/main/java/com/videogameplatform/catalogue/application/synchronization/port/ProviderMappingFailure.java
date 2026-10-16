package com.videogameplatform.catalogue.application.synchronization.port;

/**
 * Why one provider record could not become a coherent product record.
 *
 * <p>The reason is a closed product vocabulary, so it can label a metric and a run counter. The
 * offending provider value is deliberately absent.
 */
public enum ProviderMappingFailure {
    FEATURED_EVIDENCE_INVALID,
    /**
     * The summary, company credits, genres or game modes were not coherent; the Game keeps its last
     * valid details and is otherwise synchronized.
     */
    DETAILS_INVALID,
    /**
     * The player score and its rating count did not form one usable pair or a stated absence: a
     * partial pair, a mean outside its declared scale or a count that is not a whole number. The
     * Game keeps its last valid score and is otherwise synchronized.
     */
    PLAYER_SCORE_INVALID,
    /** Date value and precision did not form one valid closed variant (REL-003). */
    RELEASE_DATE_INVALID,
    /** The cover reference did not satisfy the approved ADR-0001 shape. */
    COVER_REFERENCE_INVALID,
    /**
     * An artwork, screenshot or logo did not satisfy the approved ADR-0001 shape or stated no usable
     * dimensions; it is ignored and never fails the Game.
     */
    IMAGE_REFERENCE_INVALID,
    /** The record was structurally unreadable. */
    RECORD_UNREADABLE
}
