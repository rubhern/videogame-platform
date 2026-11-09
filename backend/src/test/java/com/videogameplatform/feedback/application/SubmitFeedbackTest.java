package com.videogameplatform.feedback.application;

import static com.videogameplatform.feedback.application.SubmitFeedback.Category.OTHER;
import static com.videogameplatform.feedback.application.SubmitFeedback.Outcome.ACCEPTED;
import static com.videogameplatform.feedback.application.SubmitFeedback.Outcome.BUSY;
import static com.videogameplatform.feedback.application.SubmitFeedback.Outcome.INVALID_EMAIL;
import static com.videogameplatform.feedback.application.SubmitFeedback.Outcome.INVALID_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SubmitFeedbackTest {

    @Test
    void acceptsUnicodeWithinLimitAndRejectsOversizedOrBlankMessages() {
        var feedback = new SubmitFeedback(message -> true);
        assertThat(feedback.submit(OTHER, "🎮".repeat(2000), null)).isEqualTo(ACCEPTED);
        assertThat(feedback.submit(OTHER, "🎮".repeat(2001), null)).isEqualTo(INVALID_MESSAGE);
        assertThat(feedback.submit(OTHER, " \u00a0\t", null)).isEqualTo(INVALID_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "@example.invalid",
                "a..b@example.invalid",
                "a@example",
                "a@-example.invalid",
                "a@example.invalid.",
                "a@b@example.invalid",
                "a@example.invalid\r\nBcc: owner@example.invalid"
            })
    void rejectsMalformedReplyAddressesBeforeDelivery(String address) {
        var feedback =
                new SubmitFeedback(
                        message -> {
                            throw new AssertionError(
                                    "Invalid feedback reached the receiving channel");
                        });
        assertThat(feedback.submit(OTHER, "Feedback", address)).isEqualTo(INVALID_EMAIL);
    }

    @Test
    void enforcesReplyAddressLengthAndAcceptsDotAtomsAndDnsLabels() {
        var feedback = new SubmitFeedback(message -> true);
        assertThat(feedback.submit(OTHER, "Feedback", "a.b+tag@example-domain.invalid"))
                .isEqualTo(ACCEPTED);
        assertThat(feedback.submit(OTHER, "Feedback", "a".repeat(64) + "@example.invalid"))
                .isEqualTo(ACCEPTED);
        assertThat(feedback.submit(OTHER, "Feedback", "a".repeat(65) + "@example.invalid"))
                .isEqualTo(INVALID_EMAIL);
        assertThat(
                        feedback.submit(
                                OTHER,
                                "Feedback",
                                "a@" + ("b".repeat(63) + ".").repeat(4) + "invalid"))
                .isEqualTo(INVALID_EMAIL);
    }

    @Test
    void doesNotQueueOrSendWhenBothDeliverySlotsAreBusy() throws Exception {
        var entered = new CountDownLatch(2);
        var finish = new CountDownLatch(1);
        var feedback =
                new SubmitFeedback(
                        message -> {
                            entered.countDown();
                            try {
                                return finish.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException _) {
                                Thread.currentThread().interrupt();
                                return false;
                            }
                        });

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = threads.submit(() -> feedback.submit(OTHER, "First", null));
            var second = threads.submit(() -> feedback.submit(OTHER, "Second", null));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(feedback.submit(OTHER, "Third", null)).isEqualTo(BUSY);
            } finally {
                finish.countDown();
            }
            assertThat(first.get()).isEqualTo(ACCEPTED);
            assertThat(second.get()).isEqualTo(ACCEPTED);
        }
        assertThat(feedback.submit(OTHER, "Deliberate retry", null)).isEqualTo(ACCEPTED);
    }
}
