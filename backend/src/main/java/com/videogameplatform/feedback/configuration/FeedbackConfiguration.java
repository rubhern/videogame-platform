package com.videogameplatform.feedback.configuration;

import com.videogameplatform.feedback.adapter.SmtpFeedbackReceiver;
import com.videogameplatform.feedback.application.SubmitFeedback;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration(proxyBeanMethods = false)
class FeedbackConfiguration {

    @Bean
    SubmitFeedback submitFeedback(
            ObjectProvider<JavaMailSender> sender,
            @Value("${feedback.enabled}") boolean enabled,
            @Value("${spring.mail.host}") String host,
            @Value("${feedback.from}") String from,
            @Value("${feedback.inbox}") String inbox) {
        if (!enabled) {
            return new SubmitFeedback(message -> false);
        }
        var mail = sender.getIfAvailable();
        if (mail == null || host.isBlank() || !singleMailbox(from) || !singleMailbox(inbox)) {
            throw new IllegalStateException(
                    "Enabled feedback requires SMTP and two configured mailboxes");
        }
        return new SubmitFeedback(new SmtpFeedbackReceiver(mail, from, inbox));
    }

    private static boolean singleMailbox(String value) {
        if (value.isBlank() || value.codePoints().anyMatch(Character::isISOControl)) {
            return false;
        }
        try {
            var address = new InternetAddress(value, true);
            address.validate();
            return !address.isGroup() && value.equals(address.getAddress());
        } catch (AddressException invalid) {
            return false;
        }
    }
}
