package com.videogameplatform.api.delivery;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FrontendRouteControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FrontendRouteController()).build();
    }

    @Test
    void forwardsOnlyApprovedBrowserRoutesToThePackagedSpa() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/games/pragmata"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void doesNotCaptureServerOwnedRouteRoots() throws Exception {
        mockMvc.perform(get("/api")).andExpect(status().isNotFound());
        mockMvc.perform(get("/auth")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator")).andExpect(status().isNotFound());
    }
}
