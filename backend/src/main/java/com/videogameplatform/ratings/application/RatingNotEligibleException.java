package com.videogameplatform.ratings.application;

import com.videogameplatform.ratings.domain.RatingEligibilityPolicy;

public final class RatingNotEligibleException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String reason;

    public RatingNotEligibleException(RatingEligibilityPolicy.Reason reason) {
        super("Current release evidence does not authorize this rating command");
        this.reason = reason.name();
    }

    public String reason() {
        return reason;
    }
}
