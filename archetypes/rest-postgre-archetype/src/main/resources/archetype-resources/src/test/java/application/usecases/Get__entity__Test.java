#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.application.usecases;

import ${package}.application.query.${entity}Handler;
import ${package}.application.query.${entity}Query;
import ${package}.domain.exception.${entity}NotFoundException;
import ${package}.domain.model.entities.${entity};
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Get${entity}Test {

    @Mock
    private ${entity}Handler handler;
    @InjectMocks
    private Get${entity} get${entity}UseCase;

    @Test
    void should_delegate_to_handler_and_return_result() {
        // Given
        Long id = 1L;

        ${entity} expected${entity} = ${entity}.builder()
                .id(id)
                .build();

        when(handler.handle(any(${entity}Query.class))).thenReturn(Optional.of(expected${entity}));

        // When
        ${entity} result = get${entity}UseCase.apply(id);

        // Then
        assertEquals(expected${entity}, result);

        verify(handler, times(1)).handle(argThat(query ->
                query.getId().equals(id)));
    }

    @Test
    void should_throw_exception_when_no_${entity}_found() {
        // Given
        Long id = 1L;

        when(handler.handle(any(${entity}Query.class))).thenReturn(Optional.empty());

        // When + Then
        assertThrows(${entity}NotFoundException.class, () ->
                get${entity}UseCase.apply(id)
        );

        verify(handler, times(1)).handle(any(${entity}Query.class));
    }
}