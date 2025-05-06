#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.domain.model.entities;

import static java.util.Objects.requireNonNull;

public class ${entity} {

    private final Long id;

    private ${entity}(Builder builder) {
        this.id = requireNonNull(builder.id, "Id is required");
    }

    // Getters
    public Long getId() { return id; }

    // Builder
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public ${entity} build() {
            return new ${entity}(this);
        }
    }
}
