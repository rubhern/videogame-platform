#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.infrastructure.adapter.out.persistence;

import ${package}.infrastructure.adapter.out.persistence.model.${entity}Entity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ${entity}JpaRepository extends JpaRepository<${entity}Entity, Long> {

    Optional<${entity}Entity> find${entity}EntityById(final Long id);
}
