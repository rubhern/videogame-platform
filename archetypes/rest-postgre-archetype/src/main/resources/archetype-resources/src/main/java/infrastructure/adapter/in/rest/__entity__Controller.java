#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.infrastructure.adapter.in.rest;

import ${package}.application.usecases.Get${entity};
import ${package}.infrastructure.adapter.in.rest.api.${entity}sApi;
import ${package}.infrastructure.adapter.in.rest.dto.${entity}Response;
import ${package}.infrastructure.adapter.in.rest.mapper.${entity}RestMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ${entity}Controller implements ${entity}sApi {

    private final Get${entity} get${entity};
    private final ${entity}RestMapper ${uncapitalizedEntity}RestMapper;

    public ${entity}Controller(Get${entity} get${entity}, ${entity}RestMapper ${uncapitalizedEntity}RestMapper) {
        this.get${entity} = get${entity};
        this.${uncapitalizedEntity}RestMapper = ${uncapitalizedEntity}RestMapper;
    }

    @Override
    public ResponseEntity<${entity}Response> get${entity}ById(Long id) {
        var ${uncapitalizedEntity} = get${entity}.apply(id);
        return ResponseEntity.ok(${uncapitalizedEntity}RestMapper.toResponse(${uncapitalizedEntity}));
    }
}
