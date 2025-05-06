#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.infrastructure.adapter.in.rest.mapper;

import ${package}.domain.model.entities.${entity};
import ${package}.infrastructure.adapter.in.rest.dto.${entity}Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ${entity}RestMapperTest {
    private final ${entity}RestMapper mapper = new ${entity}RestMapperImpl();

    @Test
    void should_map_${entity}_entity_to_response_dto_correctly() {
        // Given
        ${entity} ${uncapitalizedEntity} = ${entity}.builder()
                .id(1L)
                .build();

        // When
        ${entity}Response response = mapper.toResponse(${uncapitalizedEntity});

        // Then
        assertEquals(1L, response.getId());
    }
}