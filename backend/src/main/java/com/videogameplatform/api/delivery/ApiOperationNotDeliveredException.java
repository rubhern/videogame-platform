package com.videogameplatform.api.delivery;

/**
 * A contract operation that this slice does not deliver yet.
 *
 * <p>The generated Spring interfaces are grouped by contract tag, so implementing one operation
 * of a tag also routes its siblings. Raising this keeps an undelivered sibling behaving exactly
 * as it did while unrouted — an absent resource — instead of inventing a response for it.
 */
public final class ApiOperationNotDeliveredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ApiOperationNotDeliveredException() {
        super("API operation is not delivered yet");
    }
}
