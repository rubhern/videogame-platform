#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.infrastructure.adapter.out.persistence;

import ${package}.domain.model.entities.${entity};
import ${package}.domain.ports.out.repository.${entity}Service;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ${entity}RepositoryAdapterIT {

    @Autowired
    private ${entity}Service adapter;

    @Test
    void should_return_${entity}_from_database() {
        // Given
        Long id = 1L;

        // When
        Optional<${entity}> optional${entity} = adapter.find${entity}(id);

        // Then
        assertFalse(optional${entity}.isEmpty());
        ${entity} ${uncapitalizedEntity} = optional${entity}.get();
        assertEquals(id, ${uncapitalizedEntity}.getId());
    }
}
