package com.videogameplatform.api.delivery.frontend;

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
        mockMvc.perform(get("/search"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        // A shared search result must survive a full page load, query string included.
        mockMvc.perform(get("/search").param("q", "resident evil"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        // The release explorer reloads with its whole range, order and filter state.
        mockMvc.perform(
                        get("/lanzamientos")
                                .param("from", "2026-08-01")
                                .param("to", "2026-08-31")
                                .param("sort", "hype"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/mis-puntuaciones"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/mis-puntuaciones/estadisticas"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        // The first-run platform choice and its later management are React routes too.
        mockMvc.perform(get("/bienvenida"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/mis-plataformas"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/administracion/catalogo").param("year", "1997"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/games/pragmata"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void doesNotCaptureServerOwnedRouteRoots() throws Exception {
        mockMvc.perform(get("/api")).andExpect(status().isNotFound());
        mockMvc.perform(get("/auth")).andExpect(status().isNotFound());
        mockMvc.perform(get("/login")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator")).andExpect(status().isNotFound());
        mockMvc.perform(get("/login/oauth2/code/keycloak")).andExpect(status().isNotFound());
    }
}
