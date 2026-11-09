package com.videogameplatform.feedback.application;

import static com.videogameplatform.feedback.application.SubmitFeedback.Category.OTHER;
import static com.videogameplatform.feedback.application.SubmitFeedback.Outcome.ACCEPTED;
import static com.videogameplatform.feedback.application.SubmitFeedback.Outcome.BUSY;
import static com.videogameplatform.feedback.application.SubmitFeedback.Outcome.INVALID_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SubmitFeedbackTest {

    @Test
    void acceptsUnicodeWithinLimitAndRejectsOversizedOrBlankMessages() {
        var feedback = new SubmitFeedback(message -> true);
        assertThat(feedback.submit(OTHER, "🎮".repeat(2000), null)).isEqualTo(ACCEPTED);
        assertThat(feedback.submit(OTHER, "🎮".repeat(2001), null)).isEqualTo(INVALID_MESSAGE);
        assertThat(feedback.submit(OTHER, " \u00a0\t", null)).isEqualTo(INVALID_MESSAGE);
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
                            } catch (InterruptedException interrupted) {
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
