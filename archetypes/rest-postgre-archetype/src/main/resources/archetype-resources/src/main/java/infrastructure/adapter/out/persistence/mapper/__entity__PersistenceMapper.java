#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.infrastructure.adapter.out.persistence.mapper;


import ${package}.domain.model.entities.${entity};
import ${package}.infrastructure.adapter.out.persistence.model.${entity}Entity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ${entity}PersistenceMapper {

    ${entity} toDomain(${entity}Entity entity);

    ${entity}Entity toEntity(${entity} ${uncapitalizedEntity});
}
