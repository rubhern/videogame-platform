package com.videogameplatform.ratings.domain;

/** Integer personal score constrained by RAT-001. */
public record RatingValue(int value) {
    public RatingValue {
        if (value < 1 || value > 10) {
            throw new IllegalArgumentException("Rating value must be between 1 and 10");
        }
    }
}
