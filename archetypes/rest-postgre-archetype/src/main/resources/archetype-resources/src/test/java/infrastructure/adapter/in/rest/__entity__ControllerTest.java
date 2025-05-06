#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.infrastructure.adapter.in.rest;

import ${package}.application.usecases.Get${entity};
import ${package}.domain.model.entities.${entity};
import ${package}.infrastructure.adapter.in.rest.dto.${entity}Response;
import ${package}.infrastructure.adapter.in.rest.mapper.${entity}RestMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ${entity}ControllerTest {

    @Mock
    private Get${entity} service;

    @Mock
    private ${entity}RestMapper mapper;

    @InjectMocks
    private ${entity}Controller controller;

    @Test
    void should_return_response_when_${entity}_found() {
        // Given
        Long id = 1L;

        ${entity} ${entity} = mock(${entity}.class);
        ${entity}Response response = mock(${entity}Response.class);

        when(service.apply(id)).thenReturn(${entity});
        when(mapper.toResponse(${entity})).thenReturn(response);

        // When
        ResponseEntity<${entity}Response> result = controller.get${entity}ById(id);

        // Then
        assertEquals(200, result.getStatusCode().value());
        assertEquals(response, result.getBody());
    }
}