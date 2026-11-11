package com.videogameplatform.feedback.adapter;

import com.videogameplatform.feedback.application.SubmitFeedback.Message;
import com.videogameplatform.feedback.application.SubmitFeedback.Receiver;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.eclipse.angus.mail.util.MailConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** Sends one plain-text message with operator-owned SMTP envelope and headers. */
public final class SmtpFeedbackReceiver implements Receiver {

    private static final Logger LOGGER = LoggerFactory.getLogger(SmtpFeedbackReceiver.class);

    private final JavaMailSender mail;
    private final String from;
    private final String inbox;

    public SmtpFeedbackReceiver(JavaMailSender mail, String from, String inbox) {
        this.mail = mail;
        this.from = from;
        this.inbox = inbox;
    }

    @Override
    public boolean accept(Message feedback) {
        try {
            var message = mail.createMimeMessage();
            var helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(inbox);
            helper.setSubject(
                    "Gameómetro · "
                            + switch (feedback.category()) {
                                case SUGGESTION -> "Sugerencia";
                                case BUG -> "Error o problema";
                                case INCORRECT_GAME_INFORMATION -> "Datos de un juego";
                                case OTHER -> "Otro comentario";
                            });
            // Keep visitor input in the plain-text body, including the optional reply address.
            helper.setText(
                    feedback.text()
                            + (feedback.email() == null
                                    ? ""
                                    : "\n\nCorreo para responder: " + feedback.email()));
            mail.send(message);
            return true;
        } catch (MailException | MessagingException exception) {
            // Exception messages and nested causes may contain addresses or SMTP commands, so only
            // a fixed reason, the numeric SMTP reply and the innermost exception type are logged.
            var failure = Failure.of(exception);
            LOGGER.warn(
                    "Feedback SMTP acceptance could not be confirmed: reason={} smtpCode={} cause={}",
                    failure.reason(),
                    failure.smtpCode(),
                    failure.cause());
            return false;
        }
    }

    /** A bounded, address-free description of why the relay did not confirm acceptance. */
    record Failure(String reason, String smtpCode, String cause) {

        private static final int MAX_CAUSES = 32;

        static Failure of(Throwable exception) {
            List<Throwable> causes = causes(exception);
            return new Failure(
                    reason(causes), smtpCode(causes), causes.getLast().getClass().getSimpleName());
        }

        private static String reason(List<Throwable> causes) {
            if (any(causes, MailAuthenticationException.class)
                    || any(causes, AuthenticationFailedException.class)) {
                return "authentication";
            }
            if (any(causes, SSLException.class)) {
                return "tls";
            }
            if (any(causes, SocketTimeoutException.class)) {
                return "timeout";
            }
            if (any(causes, SMTPSenderFailedException.class)) {
                return "sender-rejected";
            }
            if (any(causes, SMTPAddressFailedException.class)) {
                return "recipient-rejected";
            }
            if (any(causes, SMTPSendFailedException.class)) {
                return "message-rejected";
            }
            if (any(causes, MailConnectException.class)
                    || any(causes, UnknownHostException.class)
                    || any(causes, SocketException.class)) {
                return "connection";
            }
            if (any(causes, MailPreparationException.class)) {
                return "preparation";
            }
            return "other";
        }

        private static String smtpCode(List<Throwable> causes) {
            for (Throwable cause : causes) {
                int code =
                        switch (cause) {
                            case SMTPSenderFailedException sender -> sender.getReturnCode();
                            case SMTPAddressFailedException address -> address.getReturnCode();
                            case SMTPSendFailedException send -> send.getReturnCode();
                            default -> -1;
                        };
                if (code >= 200 && code <= 599) {
                    return Integer.toString(code);
                }
            }
            return "none";
        }

        private static boolean any(List<Throwable> causes, Class<? extends Throwable> type) {
            return causes.stream().anyMatch(type::isInstance);
        }

        /** The exception, its cause chain and Spring's per-message failures, without cycles. */
        private static List<Throwable> causes(Throwable exception) {
            List<Throwable> result = new ArrayList<>();
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            List<Throwable> pending = new ArrayList<>(List.of(exception));
            while (!pending.isEmpty() && result.size() < MAX_CAUSES) {
                Throwable next = pending.removeFirst();
                if (next == null || !seen.add(next)) {
                    continue;
                }
                result.add(next);
                pending.add(next.getCause());
                if (next instanceof MessagingException messaging) {
                    pending.add(messaging.getNextException());
                }
                if (next instanceof MailSendException send) {
                    pending.addAll(List.of(send.getMessageExceptions()));
                }
            }
            return result;
        }
    }
}
