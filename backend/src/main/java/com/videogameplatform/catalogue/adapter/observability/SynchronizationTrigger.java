package com.videogameplatform.catalogue.adapter.observability;

/** Closed trigger and policy metric vocabularies; configuration never supplies tag values. */
public enum SynchronizationTrigger {
    MANUAL("manual", "none"),
    NEAR_TERM("scheduled", "near_term"),
    UPCOMING("scheduled", "upcoming");

    private final String type;
    private final String policy;

    SynchronizationTrigger(String type, String policy) {
        this.type = type;
        this.policy = policy;
    }

    public String type() {
        return type;
    }

    public String policy() {
        return policy;
    }
}
