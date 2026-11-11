package com.videogameplatform.feedback.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.videogameplatform.feedback.application.SubmitFeedback.Category;
import com.videogameplatform.feedback.application.SubmitFeedback.Message;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.Properties;
import javax.net.ssl.SSLHandshakeException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.eclipse.angus.mail.util.MailConnectException;
import org.eclipse.angus.mail.util.SocketConnectException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;

class SmtpFeedbackReceiverTest {

    private static final String PRIVATE = "visitor@example.test";
    private static final Message FEEDBACK =
            new Message(Category.BUG, "Texto privado del visitante", PRIVATE);

    private final Logger logger = (Logger) LoggerFactory.getLogger(SmtpFeedbackReceiver.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
    }

    @Test
    void acceptedMessageLogsNothing() {
        assertThat(receiver(null).accept(FEEDBACK)).isTrue();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void rejectedCredentialsAreAnAuthenticationFailure() {
        var failure =
                new MailAuthenticationException(
                        new AuthenticationFailedException("535 bad credentials for " + PRIVATE));

        assertLogged(
                failure, "reason=authentication smtpCode=none cause=AuthenticationFailedException");
    }

    @Test
    void unreachableRelayIsAConnectionFailure() {
        var failure =
                new MailSendException(
                        "Mail server connection failed",
                        new MailConnectException(
                                new SocketConnectException(
                                        "refused", new ConnectException(), "relay", 587, 3000)));

        assertLogged(failure, "reason=connection smtpCode=none cause=ConnectException");
    }

    @Test
    void failedStartTlsHandshakeIsATlsFailure() {
        var failure =
                new MailSendException(
                        "Mail server connection failed",
                        new MessagingException(
                                "Could not convert socket to TLS",
                                new SSLHandshakeException("PKIX path building failed")));

        assertLogged(failure, "reason=tls smtpCode=none cause=SSLHandshakeException");
    }

    @Test
    void slowRelayIsATimeout() {
        var failure =
                new MailSendException(
                        "Mail server connection failed",
                        new MessagingException(
                                "read", new SocketTimeoutException("Read timed out")));

        assertLogged(failure, "reason=timeout smtpCode=none cause=SocketTimeoutException");
    }

    @Test
    void perMessageRefusalsKeepOnlyTheirReasonAndReplyCode() throws Exception {
        var address = new InternetAddress(PRIVATE);
        var recipient =
                new SendFailedException(
                        "Invalid Addresses",
                        new SMTPAddressFailedException(
                                address, "RCPT TO", 550, "550 no " + PRIVATE),
                        null,
                        null,
                        new InternetAddress[] {address});
        var sender =
                new SMTPSenderFailedException(address, "MAIL FROM", 553, "553 sender " + PRIVATE);
        var message =
                new SMTPSendFailedException(
                        "DATA", 554, "554 rejected " + PRIVATE, null, null, null, null);

        assertLogged(
                perMessage(recipient),
                "reason=recipient-rejected smtpCode=550 cause=SMTPAddressFailedException");
        assertLogged(
                perMessage(sender),
                "reason=sender-rejected smtpCode=553 cause=SMTPSenderFailedException");
        assertLogged(
                perMessage(message),
                "reason=message-rejected smtpCode=554 cause=SMTPSendFailedException");
    }

    @Test
    void anUnclassifiedFailureStillNamesItsType() {
        assertLogged(
                new MailSendException("odd " + PRIVATE),
                "reason=other smtpCode=none cause=MailSendException");
    }

    private void assertLogged(MailException failure, String expected) {
        appender.list.clear();

        assertThat(receiver(failure).accept(FEEDBACK)).isFalse();

        assertThat(appender.list)
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getFormattedMessage())
                                    .isEqualTo(
                                            "Feedback SMTP acceptance could not be confirmed: "
                                                    + expected)
                                    .doesNotContain(PRIVATE);
                            assertThat(event.getThrowableProxy()).isNull();
                        });
    }

    private static MailSendException perMessage(Exception refusal) {
        return new MailSendException(Map.of(new Object(), refusal));
    }

    private static SmtpFeedbackReceiver receiver(MailException failure) {
        return new SmtpFeedbackReceiver(
                new FailingSender(failure), "from@example.test", "inbox@example.test");
    }

    private record FailingSender(MailException failure) implements JavaMailSender {

        @Override
        public MimeMessage createMimeMessage() {
            return new MimeMessage(Session.getInstance(new Properties()));
        }

        @Override
        public MimeMessage createMimeMessage(InputStream contentStream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void send(MimeMessage... mimeMessages) {
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void send(MimeMessagePreparator... mimeMessagePreparators) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void send(SimpleMailMessage... simpleMessages) {
            throw new UnsupportedOperationException();
        }
    }
}
