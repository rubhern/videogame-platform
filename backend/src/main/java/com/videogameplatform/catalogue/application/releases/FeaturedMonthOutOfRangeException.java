package com.videogameplatform.catalogue.application.releases;

/**
 * The requested featured month lies outside the current calendar year at the trusted evaluation
 * date. Featured discovery presents the months of the current year only, never another year's.
 */
public final class FeaturedMonthOutOfRangeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FeaturedMonthOutOfRangeException() {
        super("A featured month must fall in the current calendar year");
    }
}
