package com.videogameplatform.catalogue.application.synchronization.port;

/**
 * Why one provider record could not become a coherent product record.
 *
 * <p>The reason is a closed product vocabulary, so it can label a metric and a run counter. The
 * offending provider value is deliberately absent.
 */
public enum ProviderMappingFailure {
    /** The provider platform has no curated product platform; releases are never merged (REL-005). */
    PLATFORM_NOT_SUPPORTED,
    /** The provider region has no curated product region, so the tuple stays unpublished. */
    REGION_NOT_SUPPORTED,
    /** Date value and precision did not form one valid closed variant (REL-003). */
    RELEASE_DATE_INVALID,
    /** The cover reference did not satisfy the approved ADR-0001 shape. */
    COVER_REFERENCE_INVALID,
    /** Two provider records normalized to one identical tuple, which would merge them silently. */
    DUPLICATE_RELEASE_TUPLE,
    /** The record was structurally unreadable. */
    RECORD_UNREADABLE
}
