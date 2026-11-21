package com.videogameplatform.catalogue.application.wishlist;

public final class WishlistGameNotFoundException extends RuntimeException {
    public WishlistGameNotFoundException() {
        super("The game is absent from the current user's wishlist");
    }
}
