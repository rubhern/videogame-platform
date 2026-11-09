package com.videogameplatform.api.delivery.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.feedback.adapter.SmtpFeedbackReceiver;
import com.videogameplatform.feedback.application.SubmitFeedback;
import com.videogameplatform.identity.configuration.AuthenticationProblemEntryPoint;
import com.videogameplatform.identity.configuration.CsrfProblemAccessDeniedHandler;
import com.videogameplatform.identity.configuration.IdentitySecurityConfiguration;
import com.videogameplatform.identity.configuration.RatingIntentAuthenticationFailureHandler;
import com.videogameplatform.identity.configuration.RatingResumeAuthenticationSuccessHandler;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FeedbackController.class)
@Import({
    FeedbackHttpTest.Fixture.class,
    IdentitySecurityConfiguration.class,
    AuthenticationProblemEntryPoint.class,
    CsrfProblemAccessDeniedHandler.class,
    RatingResumeAuthenticationSuccessHandler.class,
    RatingIntentAuthenticationFailureHandler.class
})
class FeedbackHttpTest {

    private static final String PATH = "/api/v1/feedback";
    private static final String VALID =
            """
            {"category":"bug","message":"Falla la búsqueda.\n","email":"player@example.invalid"}
            """
                    .replace("búsqueda.\n", "búsqueda.\\n");

    @Autowired MockMvc mvc;
    @Autowired JavaMailSenderImpl mail;

    @BeforeEach
    void resetDestination() {
        mail.setPort(0);
    }

    @Test
    void anonymousSendOnlyConfirmsAfterFinalSmtpAcceptance() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            mail.setPort(server.getLocalPort());
            var accepted = answerSmtp(server, "250 Message accepted");
            mvc.perform(
                            submission(VALID)
                                    .header("Sec-Fetch-Site", "same-origin")
                                    .header("Origin", "http://localhost"))
                    .andExpect(status().isNoContent())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().exists("X-Correlation-ID"));

            MimeMessage message = accepted.get(5, TimeUnit.SECONDS);
            assertThat(message.getAllRecipients())
                    .extracting(Object::toString)
                    .containsExactly("owner@example.invalid");
            assertThat(message.getFrom())
                    .extracting(Object::toString)
                    .containsExactly("gameometro@example.invalid");
            assertThat(message.getSubject()).isEqualTo("Gameómetro · Error o problema");
            assertThat(message.getContent().toString())
                    .contains(
                            "Falla la búsqueda.", "Correo para responder: player@example.invalid");
            assertThat(message.getHeader("Reply-To")).isNull();
        }
    }

    @Test
    void finalSmtpRejectionDoesNotExposeAddressOrMessage() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            mail.setPort(server.getLocalPort());
            var rejected = answerSmtp(server, "550 Refused player@example.invalid");
            var response =
                    mvc.perform(submission(VALID))
                            .andExpect(status().isServiceUnavailable())
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andExpect(header().string("Retry-After", "30"))
                            .andExpect(jsonPath("$.code").value("FEEDBACK_UNAVAILABLE"))
                            .andReturn()
                            .getResponse();
            assertThat(response.getContentAsString())
                    .doesNotContain("player@example.invalid", "Falla");
            rejected.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsCrossOriginBeforeSending() throws Exception {
        mvc.perform(submission(VALID).header("Origin", "https://elsewhere.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_VALIDATION_FAILED"));
        mvc.perform(submission(VALID).header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsInvalidOrUnexpectedInputWithoutEchoingIt() throws Exception {
        mvc.perform(
                        submission(
                                """
                        {"category":"suggestion","message":"  "}
                        """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].pointer").value("/body/message"));
        mvc.perform(
                        submission(
                                """
                        {"category":"suggestion","message":"Text","email":"fake@example.invalid\\r\\nBcc: owner@example.invalid"}
                        """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].pointer").value("/body/email"));
        mvc.perform(
                        submission(
                                """
                        {"category":"other","message":12}
                        """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FEEDBACK_INVALID"));
        mvc.perform(
                        submission(
                                """
                        {"category":"other","message":"Text","credentials":"secret"}
                        """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REQUEST_PROPERTY_UNKNOWN"));
        mvc.perform(
                        post(PATH + "?unexpected=true")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REQUEST_PARAMETER_UNKNOWN"));
        mvc.perform(submission(VALID + "{}")).andExpect(status().isBadRequest());
        mvc.perform(submission(" ".repeat(32 * 1024) + VALID))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
        mvc.perform(post(PATH).contentType(MediaType.TEXT_PLAIN).content(VALID))
                .andExpect(status().isUnsupportedMediaType());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            submission(String body) {
        return post(PATH).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** A disposable SMTP peer that decides the result only after receiving the full DATA block. */
    private static CompletableFuture<MimeMessage> answerSmtp(
            ServerSocket server, String dataReply) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try (var socket = server.accept();
                            var reader =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    socket.getInputStream(),
                                                    StandardCharsets.US_ASCII));
                            var writer =
                                    new BufferedWriter(
                                            new OutputStreamWriter(
                                                    socket.getOutputStream(),
                                                    StandardCharsets.US_ASCII))) {
                        socket.setSoTimeout(5000);
                        reply(writer, "220 fixture SMTP");
                        StringBuilder data = new StringBuilder();
                        for (String command; (command = reader.readLine()) != null; ) {
                            if (command.equals("DATA")) {
                                reply(writer, "354 Send message");
                                for (String line;
                                        (line = reader.readLine()) != null && !line.equals("."); ) {
                                    data.append(line).append("\r\n");
                                }
                                reply(writer, dataReply);
                            } else if (command.equals("QUIT")) {
                                break;
                            } else {
                                reply(writer, "250 OK");
                            }
                        }
                        return new MimeMessage(
                                Session.getInstance(new Properties()),
                                new ByteArrayInputStream(
                                        data.toString().getBytes(StandardCharsets.US_ASCII)));
                    } catch (Exception failure) {
                        throw new AssertionError("SMTP fixture failed", failure);
                    }
                });
    }

    private static void reply(BufferedWriter writer, String response) throws java.io.IOException {
        writer.write(response + "\r\n");
        writer.flush();
    }

    @TestConfiguration
    static class Fixture {
        @Bean
        io.micrometer.core.instrument.MeterRegistry meterRegistry() {
            return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        }

        @Bean
        com.videogameplatform.identity.adapter.session.RatingReturnContextStore
                ratingReturnContextStore(
                        jakarta.servlet.http.HttpServletRequest request, Clock clock) {
            return new com.videogameplatform.identity.adapter.session.RatingReturnContextStore(
                    request, clock, java.time.Duration.ofMinutes(10));
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        JavaMailSenderImpl mail() {
            var sender = new JavaMailSenderImpl();
            sender.setHost(InetAddress.getLoopbackAddress().getHostAddress());
            sender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "1000");
            sender.getJavaMailProperties().setProperty("mail.smtp.timeout", "5000");
            sender.getJavaMailProperties().setProperty("mail.smtp.quitwait", "false");
            return sender;
        }

        @Bean
        SubmitFeedback feedback(JavaMailSenderImpl mail) {
            return new SubmitFeedback(
                    new SmtpFeedbackReceiver(
                            mail, "gameometro@example.invalid", "owner@example.invalid"));
        }
    }
}
