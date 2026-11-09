package com.videogameplatform.feedback.application;

import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/** Synchronously hands a bounded message to the receiving channel. */
public class SubmitFeedback {

    private static final Pattern EMAIL =
            Pattern.compile(
                    "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
                            + "@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                            + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+");

    private final Receiver receiver;
    private final Semaphore deliverySlots = new Semaphore(2);

    public SubmitFeedback(Receiver receiver) {
        this.receiver = receiver;
    }

    public Outcome submit(Category category, String message, String email) {
        if (category == null) {
            return Outcome.INVALID_CATEGORY;
        }
        if (message == null
                || message.codePointCount(0, message.length()) > 2000
                || message.codePoints()
                        .allMatch(
                                value ->
                                        Character.isWhitespace(value)
                                                || Character.isSpaceChar(value))
                || message.codePoints()
                        .anyMatch(
                                value ->
                                        (Character.isISOControl(value)
                                                        && value != '\t'
                                                        && value != '\r'
                                                        && value != '\n')
                                                || (value >= Character.MIN_SURROGATE
                                                        && value <= Character.MAX_SURROGATE))) {
            return Outcome.INVALID_MESSAGE;
        }
        if (email != null
                && (email.length() > 254
                        || !EMAIL.matcher(email).matches()
                        || email.indexOf('@') > 64)) {
            return Outcome.INVALID_EMAIL;
        }
        if (!deliverySlots.tryAcquire()) {
            return Outcome.BUSY;
        }
        try {
            return receiver.accept(new Message(category, message.strip(), email))
                    ? Outcome.ACCEPTED
                    : Outcome.UNAVAILABLE;
        } finally {
            deliverySlots.release();
        }
    }

    public enum Category {
        SUGGESTION,
        BUG,
        INCORRECT_GAME_INFORMATION,
        OTHER
    }

    public enum Outcome {
        ACCEPTED,
        INVALID_CATEGORY,
        INVALID_MESSAGE,
        INVALID_EMAIL,
        BUSY,
        UNAVAILABLE
    }

    public record Message(Category category, String text, String email) {}

    @FunctionalInterface
    public interface Receiver {
        boolean accept(Message message);
    }
}
