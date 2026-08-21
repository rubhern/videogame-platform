package com.videogameplatform.catalogue.domain;

/** Freshness derived at one logical evaluation instant. */
public enum FreshnessStatus {
    FRESH("fresh"),
    STALE("stale");

    private final String value;

    FreshnessStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
