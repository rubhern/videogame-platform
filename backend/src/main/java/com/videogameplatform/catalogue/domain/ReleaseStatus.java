package com.videogameplatform.catalogue.domain;

/** Product release lifecycle states. */
public enum ReleaseStatus {
    ANNOUNCED("announced"),
    SCHEDULED("scheduled"),
    RELEASED("released"),
    DELAYED("delayed"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown");

    private final String value;

    ReleaseStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ReleaseStatus fromValue(String value) {
        for (ReleaseStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unsupported release status: " + value);
    }
}
