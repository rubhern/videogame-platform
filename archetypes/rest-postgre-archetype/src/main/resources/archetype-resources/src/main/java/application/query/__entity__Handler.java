#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.application.query;

import ${package}.domain.model.entities.${entity};
import ${package}.domain.ports.out.repository.${entity}Service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ${entity}Handler {

    private final ${entity}Service ${uncapitalizedEntity}Service;

    public Optional<${entity}> handle(${entity}Query query) {
        return ${uncapitalizedEntity}Service.find${entity}(
                query.getId());
    }
}
