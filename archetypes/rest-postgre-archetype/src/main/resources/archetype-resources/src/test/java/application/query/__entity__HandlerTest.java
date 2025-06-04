#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.application.query;

import ${package}.domain.model.entities.${entity};
import ${package}.domain.ports.out.repository.${entity}Service;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ${entity}HandlerTest {

    @Mock
    private ${entity}Service repository;
    @InjectMocks
    private ${entity}Handler handler;

    private static ${entity}Query query;

    @BeforeAll
    public static void setUp() {
        query = new ${entity}Query(1L);
    }

    @Test
    void should_return_${entity}() {
        // Given
        ${entity} ${uncapitalizedEntity} = ${entity}.builder()
                .id(1L)
                .build();

        when(repository.find${entity}(1L)).thenReturn(Optional.of(${uncapitalizedEntity}));

        // When
        Optional<${entity}> result = handler.handle(query);

        // Then
        assertTrue(result.isPresent());
        assertEquals(1L, result.get().getId());
    }

    @Test
    void should_return_empty_if_no_applicable_${entity}() {
        // Given
        when(repository.find${entity}(1L)).thenReturn(Optional.empty());

        // When
        Optional<${entity}> result = handler.handle(query);

        // Then
        assertTrue(result.isEmpty());
    }
}