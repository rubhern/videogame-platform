#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.application.usecases;

import ${package}.application.query.${entity}Handler;
import ${package}.application.query.${entity}Query;
import ${package}.domain.exception.${entity}NotFoundException;
import ${package}.domain.model.entities.${entity};
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.LongFunction;

@Component
public class Get${entity} implements LongFunction<${entity}> {

    private static final Logger log = LoggerFactory.getLogger(Get${entity}.class);
    private final ${entity}Handler handler;

    public Get${entity}(${entity}Handler handler) {
        this.handler = handler;
    }

    @Override
    public ${entity} apply(long id) {
    ${entity}Query query = new ${entity}Query(id);

        return handler.handle(query).orElseThrow(() -> {
            log.warn("No ${entity} found for id={}", id);
            return new ${entity}NotFoundException("No ${entity} found for id=" + id);
        });
    }
}
