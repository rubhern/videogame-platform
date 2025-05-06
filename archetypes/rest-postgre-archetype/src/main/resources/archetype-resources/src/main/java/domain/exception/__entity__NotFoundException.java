#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.domain.exception;

public class ${entity}NotFoundException extends RuntimeException {
      public ${entity}NotFoundException(String message) {
            super(message);
      }
}
