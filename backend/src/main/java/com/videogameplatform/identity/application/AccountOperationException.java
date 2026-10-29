package com.videogameplatform.identity.application;

/** Safe failure with no account details, command output or credentials. */
public final class AccountOperationException extends RuntimeException {
    public enum Reason {
        RECENT_AUTHENTICATION,
        UNAVAILABLE
    }

    private final Reason reason;

    public AccountOperationException(Reason reason) {
        super("Account operation could not be completed");
        this.reason = reason;
    }

    public AccountOperationException(Reason reason, Throwable cause) {
        super("Account operation could not be completed", cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
