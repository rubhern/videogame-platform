#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.domain.ports.out.repository;

import ${package}.domain.model.entities.${entity};

import java.util.Optional;

public interface ${entity}Service {
    Optional<${entity}> find${entity}(final Long id);
}
