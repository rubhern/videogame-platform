package com.videogameplatform.catalogue.domain;

/** Confidence level of normalized release evidence. */
public enum VerificationLevel {
    PROVIDER_ONLY("provider_only"),
    VERIFIED("verified");

    private final String value;

    VerificationLevel(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static VerificationLevel fromValue(String value) {
        for (VerificationLevel level : values()) {
            if (level.value.equals(value)) {
                return level;
            }
        }
        throw new IllegalArgumentException("Unsupported verification level: " + value);
    }
}
