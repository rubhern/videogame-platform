package com.videogameplatform.ratings.application;

/**
 * A newly named platform is not one of the game's current release platforms. Platforms already
 * saved on the rating never raise it.
 */
public final class RatingPlatformNotSupportedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int platformIndex;

    public RatingPlatformNotSupportedException(int platformIndex) {
        super("A newly named platform is not one of the game's release platforms");
        this.platformIndex = platformIndex;
    }

    /** The position of the first unsupported platform in the command. */
    public int platformIndex() {
        return platformIndex;
    }
}
