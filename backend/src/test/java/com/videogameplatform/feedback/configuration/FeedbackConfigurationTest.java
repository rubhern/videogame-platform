package com.videogameplatform.feedback.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.videogameplatform.feedback.application.SubmitFeedback.Category;
import com.videogameplatform.feedback.application.SubmitFeedback.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;

class FeedbackConfigurationTest {

    private static final String FROM = "sender@example.invalid";
    private static final String INBOX = "owner@example.invalid";
    private final FeedbackConfiguration configuration = new FeedbackConfiguration();
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();

    @Test
    void disabledFeedbackDoesNotContactTheMailSender() {
        var sender = registerMailSender();
        var feedback =
                configuration.submitFeedback(
                        beans.getBeanProvider(JavaMailSender.class), false, "", "", "");
        assertThat(feedback.submit(Category.OTHER, "Synthetic feedback", null))
                .isEqualTo(Outcome.UNAVAILABLE);
        verifyNoInteractions(sender);
    }

    @Test
    void rejectsMissingSmtpConfigurationAtStartup() {
        var sender = beans.getBeanProvider(JavaMailSender.class);
        assertThatIllegalStateException()
                .isThrownBy(
                        () -> configuration.submitFeedback(sender, true, "mailpit", FROM, INBOX));
        registerMailSender();
        assertThatIllegalStateException()
                .isThrownBy(() -> configuration.submitFeedback(sender, true, "", FROM, INBOX));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "owner",
                "Owner <owner@example.invalid>",
                "group:owner@example.invalid;",
                "owner@example.invalid\r\nBcc: other@example.invalid"
            })
    void rejectsAnInvalidOrNonBareMailboxAtStartup(String address) {
        var sender = registerMailSender();
        var provider = beans.getBeanProvider(JavaMailSender.class);
        assertThatIllegalStateException()
                .isThrownBy(
                        () ->
                                configuration.submitFeedback(
                                        provider, true, "mailpit", address, INBOX));
        assertThatIllegalStateException()
                .isThrownBy(
                        () ->
                                configuration.submitFeedback(
                                        provider, true, "mailpit", FROM, address));
        verifyNoInteractions(sender);
    }

    @Test
    void acceptsConfiguredSingleMailboxesWithoutSendingAtStartup() {
        var sender = registerMailSender();
        assertThat(
                        configuration.submitFeedback(
                                beans.getBeanProvider(JavaMailSender.class),
                                true,
                                "mailpit",
                                FROM,
                                INBOX))
                .isNotNull();
        verifyNoInteractions(sender);
    }

    private JavaMailSender registerMailSender() {
        var sender = mock(JavaMailSender.class);
        beans.registerSingleton("mail", sender);
        return sender;
    }
}
