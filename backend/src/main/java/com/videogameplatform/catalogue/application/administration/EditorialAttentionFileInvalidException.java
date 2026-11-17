package com.videogameplatform.catalogue.application.administration;

/**
 * An editorial anticipation file, or the operator's assignments for it, cannot be loaded. The
 * pointer names the offending member with a JSON Pointer into the file or the request; nothing was
 * stored.
 */
public final class EditorialAttentionFileInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String pointer;

    public EditorialAttentionFileInvalidException(String pointer, String reason) {
        super(reason);
        this.pointer = pointer;
    }

    public String pointer() {
        return pointer;
    }
}
