package com.videogameplatform.catalogue.application.search;

/**
 * The visitor query cannot address the bounded catalogue.
 *
 * <p>The rejected text is deliberately not carried on the exception: it is untrusted visitor
 * input and must not reach a log, a metric label or a Problem Details body.
 */
public final class SearchQueryInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SearchQueryInvalidException() {
        super("SEARCH_QUERY_INVALID");
    }
}
