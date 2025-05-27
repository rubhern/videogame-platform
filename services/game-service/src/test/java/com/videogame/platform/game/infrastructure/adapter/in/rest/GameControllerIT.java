package com.videogame.platform.game.infrastructure.adapter.in.rest;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(MockitoExtension.class)
class GameControllerIT {

  @Autowired private MockMvc mockMvc;

  private static final String URL = "/games";

  @Test
  void should_return_200_when_valid_request() throws Exception {
    mockMvc
        .perform(get(URL + "/{id}", 1L))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1L));
  }

  @Test
  void should_return_404_when_Game_not_found() throws Exception {
    mockMvc
        .perform(get(URL + "/{id}", 5L))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").exists());
  }

  @Test
  void should_return_400_for_invalid_param_type() throws Exception {
    mockMvc
        .perform(get(URL + "/{id}", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("Invalid value")));
  }
}
