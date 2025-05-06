#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.infrastructure.adapter.in.rest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(MockitoExtension.class)
class ${entity}ControllerIT {

    @Autowired
    private MockMvc mockMvc;

    private static final String URL = "/${uncapitalizedEntity}";

    @Test
    void should_return_200_when_valid_request() throws Exception {
        mockMvc.perform(get(URL)
                .param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("${symbol_dollar}.id").value(1L));
    }

    @Test
    void should_return_404_when_${entity}_not_found() throws Exception {
        mockMvc.perform(get(URL)
                .param("id", "5"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("${symbol_dollar}.message").exists());
    }

    @Test
    void should_return_400_for_missing_param() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("${symbol_dollar}.message").value(containsString("Missing required parameter")));
    }

    @Test
    void should_return_400_for_invalid_param_type() throws Exception {
        mockMvc.perform(get(URL)
                .param("id", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("${symbol_dollar}.message").value(containsString("Invalid value")));
    }
}