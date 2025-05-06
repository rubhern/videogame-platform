#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.infrastructure.adapter.out.persistence.mapper;

import ${package}.domain.model.entities.${entity};
import ${package}.infrastructure.adapter.out.persistence.model.${entity}Entity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ${entity}PersistenceMapperTest {

    private final ${entity}PersistenceMapper mapper = Mappers.getMapper(${entity}PersistenceMapper.class);

    @Test
    void should_map_entity_to_domain() {
        // Given
        ${entity}Entity entity = new ${entity}Entity();
        entity.setId(1L);

        // When
        ${entity} ${entity} = mapper.toDomain(entity);

        // Then
        assertEquals(1L, ${entity}.getId());
    }

    @Test
    void should_map_domain_to_entity() {
        // Given
        ${entity} ${uncapitalizedEntity} = ${entity}.builder()
                .id(1L)
                .build();

        // When
        ${entity}Entity entity = mapper.toEntity(${uncapitalizedEntity});

        // Then
        assertNotNull(entity);
        assertEquals(1L, entity.getId());
    }
}