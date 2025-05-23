#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.infrastructure.adapter.in.rest.mapper;

import ${package}.domain.model.entities.${entity};
import ${package}.infrastructure.adapter.in.rest.dto.${entity}Response;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ${entity}RestMapper {

    ${entity}Response toResponse(${entity} ${uncapitalizedEntity});
}
