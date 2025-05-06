#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.syntax.elements.ClassesShouldConjunction;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Simple naming conventions for adapters and mappers.
 */
@AnalyzeClasses(packages = "${package}")
public class NamingConventionTest {

    @ArchTest
    static final ClassesShouldConjunction controllers_should_be_in_correct_package =
            classes().that().haveSimpleNameEndingWith("Controller")
                    .should().resideInAPackage("..adapter.in.rest..");

    @ArchTest
    static final ClassesShouldConjunction mappers_should_be_in_correct_package =
            classes().that().haveSimpleNameEndingWith("Mapper")
                    .should().resideInAnyPackage("..mapper..", "..mapper..");
}