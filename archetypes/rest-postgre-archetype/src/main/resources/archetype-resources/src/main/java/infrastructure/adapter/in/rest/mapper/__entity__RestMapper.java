#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.infrastructure.adapter.in.rest.mapper;

import ${package}.domain.model.entities.${entity};
import ${package}.infrastructure.adapter.in.rest.dto.${entity}Response;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface ${entity}RestMapper {

    ${entity}Response toResponse(${entity} ${entity});
}
