package com.videogameplatform.catalogue.application.wishlist;

/** A state other than Deseado needs a release that has already occurred (UC-026). */
public final class GameNotReleasedException extends RuntimeException {
    public GameNotReleasedException() {
        super("The game has no release that has occurred");
    }
}
