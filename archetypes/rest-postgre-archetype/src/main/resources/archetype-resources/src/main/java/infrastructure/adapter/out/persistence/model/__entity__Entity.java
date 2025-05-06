#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.infrastructure.adapter.out.persistence.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "${entity}")
@Getter
@Setter
public class ${entity}Entity {

    @Id
    private Long id;
}
