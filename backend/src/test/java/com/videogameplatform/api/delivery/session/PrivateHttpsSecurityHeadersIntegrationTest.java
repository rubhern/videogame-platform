package com.videogameplatform.api.delivery.session;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.identity.configuration.AuthenticationProblemEntryPoint;
import com.videogameplatform.identity.configuration.CsrfProblemAccessDeniedHandler;
import com.videogameplatform.identity.configuration.IdentitySecurityConfiguration;
import com.videogameplatform.identity.configuration.RatingIntentAuthenticationFailureHandler;
import com.videogameplatform.identity.configuration.RatingResumeAuthenticationSuccessHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        value = SessionController.class,
        properties = "platform.http-security.hsts.enabled=true")
@Import({
    IdentitySecurityConfiguration.class,
    AuthenticationProblemEntryPoint.class,
    CsrfProblemAccessDeniedHandler.class,
    RatingResumeAuthenticationSuccessHandler.class,
    RatingIntentAuthenticationFailureHandler.class,
    SessionSecurityIntegrationTest.ClientRegistrationConfiguration.class
})
class PrivateHttpsSecurityHeadersIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void privateHttpsPolicyAddsHstsEvenThoughTailscaleTerminatesTlsBeforeSpring() throws Exception {
        mockMvc.perform(get("/api/v1/session"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Strict-Transport-Security",
                                        "max-age=31536000 ; includeSubDomains"));
    }
}
