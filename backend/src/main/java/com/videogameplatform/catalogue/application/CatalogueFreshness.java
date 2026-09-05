package com.videogameplatform.catalogue.application;

/** Whether the local catalogue data behind a read is still within the freshness threshold. */
public enum CatalogueFreshness {
    FRESH,
    STALE
}
