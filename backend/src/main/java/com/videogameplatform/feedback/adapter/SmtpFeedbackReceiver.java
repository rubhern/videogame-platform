package com.videogameplatform.feedback.adapter;

import com.videogameplatform.feedback.application.SubmitFeedback.Message;
import com.videogameplatform.feedback.application.SubmitFeedback.Receiver;
import jakarta.mail.MessagingException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
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
        } catch (MailException | MessagingException _) {
            // Exception messages and nested causes may contain addresses or SMTP commands.
            LOGGER.warn("Feedback SMTP acceptance could not be confirmed");
            return false;
        }
    }
}
