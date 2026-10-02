package com.videogameplatform.catalogue.application;

/** Product release stage exposed by catalogue reads, independent of lifecycle. */
public enum CatalogueReleaseStage {
    FULL_RELEASE,
    EARLY_ACCESS,
    ADVANCE_ACCESS,
    BETA,
    ALPHA,
    UNKNOWN
}
