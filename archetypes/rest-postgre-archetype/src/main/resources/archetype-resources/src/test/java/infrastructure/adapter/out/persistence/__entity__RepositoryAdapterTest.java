#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.infrastructure.adapter.out.persistence;

import ${package}.domain.model.entities.${entity};
import ${package}.infrastructure.adapter.out.persistence.mapper.${entity}PersistenceMapper;
import ${package}.infrastructure.adapter.out.persistence.model.${entity}Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ${entity}RepositoryAdapterTest {

    @Mock
    private ${entity}JpaRepository jpaRepository;
    @Mock
    private ${entity}PersistenceMapper mapper;
    @InjectMocks
    private ${entity}RepositoryAdapter adapter;

    @Test
    void should_return_mapped_${entity}() {
        // Given
        Long id = 1L;

        ${entity}Entity entity = mock(${entity}Entity.class);
        ${entity} ${uncapitalizedEntity} = mock(${entity}.class);

        when(jpaRepository.find${entity}EntityById(id)).thenReturn(Optional.of(entity));
        when(mapper.toDomain(entity)).thenReturn(${uncapitalizedEntity});

        // When
        Optional<${entity}> result = adapter.find${entity}(1L);

        // Then
        assertFalse(result.isEmpty());

        verify(jpaRepository).find${entity}EntityById(id);
        verify(mapper).toDomain(entity);
    }
}