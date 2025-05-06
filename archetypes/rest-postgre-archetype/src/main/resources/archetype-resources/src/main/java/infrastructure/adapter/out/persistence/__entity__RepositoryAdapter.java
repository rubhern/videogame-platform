#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.infrastructure.adapter.out.persistence;

import ${package}.domain.model.entities.${entity};
import ${package}.domain.ports.out.repository.${entity}Service;
import ${package}.infrastructure.adapter.out.persistence.mapper.${entity}PersistenceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ${entity}RepositoryAdapter implements ${entity}Service {

    private final ${entity}JpaRepository jpaRepository;
    private final ${entity}PersistenceMapper mapper;

    @Override
    public Optional<${entity}> find${entity}(Long id) {
        return jpaRepository.find${entity}EntityById(id)
                .map(mapper::toDomain);
    }
}
