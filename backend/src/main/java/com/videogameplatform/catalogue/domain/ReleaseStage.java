package com.videogameplatform.catalogue.domain;

/** Semantic release stage, independent of lifecycle status and rating eligibility. */
public enum ReleaseStage {
    FULL_RELEASE("full_release"),
    EARLY_ACCESS("early_access"),
    ADVANCE_ACCESS("advance_access"),
    BETA("beta"),
    ALPHA("alpha"),
    UNKNOWN("unknown");

    private final String value;

    ReleaseStage(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ReleaseStage fromValue(String value) {
        for (ReleaseStage stage : values()) {
            if (stage.value.equals(value)) {
                return stage;
            }
        }
        throw new IllegalArgumentException("Unsupported product release stage");
    }
}
