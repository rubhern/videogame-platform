package com.videogameplatform.catalogue.domain;

/** Whether normalized release evidence still needs human review. */
public enum ReviewStatus {
    NOT_REQUIRED("not_required"),
    REQUIRED("required");

    private final String value;

    ReviewStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ReviewStatus fromValue(String value) {
        for (ReviewStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unsupported review status: " + value);
    }
}
