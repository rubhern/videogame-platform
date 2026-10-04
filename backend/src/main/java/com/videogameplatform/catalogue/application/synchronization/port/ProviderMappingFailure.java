package com.videogameplatform.catalogue.application.synchronization.port;

/**
 * Why one provider record could not become a coherent product record.
 *
 * <p>The reason is a closed product vocabulary, so it can label a metric and a run counter. The
 * offending provider value is deliberately absent.
 */
public enum ProviderMappingFailure {
    FEATURED_EVIDENCE_INVALID,
    /** Date value and precision did not form one valid closed variant (REL-003). */
    RELEASE_DATE_INVALID,
    /** The cover reference did not satisfy the approved ADR-0001 shape. */
    COVER_REFERENCE_INVALID,
    /**
     * An artwork, screenshot or logo did not satisfy the approved ADR-0001 shape or stated no usable
     * dimensions; it is ignored and never fails the Game.
     */
    IMAGE_REFERENCE_INVALID,
    /** Two provider records normalized to one identical tuple, which would merge them silently. */
    DUPLICATE_RELEASE_TUPLE,
    /** The record was structurally unreadable. */
    RECORD_UNREADABLE
}
