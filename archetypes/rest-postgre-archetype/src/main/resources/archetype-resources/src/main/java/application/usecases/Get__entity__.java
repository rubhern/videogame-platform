#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.application.usecases;

import ${package}.application.query.${entity}Handler;
import ${package}.application.query.${entity}Query;
import ${package}.domain.exception.${entity}NotFoundException;
import ${package}.domain.model.entities.${entity};
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.LongFunction;

@Component
@RequiredArgsConstructor
@Slf4j
public class Get${entity} implements LongFunction<${entity}> {

    private final ${entity}Handler handler;

    @Override
    public ${entity} apply(long id) {
        ${entity}Query query = ${entity}Query.builder()
                .id(id)
                .build();

        return handler.handle(query).orElseThrow(() -> {
            log.warn("No ${entity} found for id={}", id);
            return new ${entity}NotFoundException("No ${entity} found for id=" + id);
        });
    }
}
