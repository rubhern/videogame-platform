#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Hexagonal‑architecture constraints for the Prices project.
 */
@AnalyzeClasses(packages = "${package}")
public class HexagonalArchitectureTest {

    private static final String DOMAIN_LAYER = "..domain..";
    private static final String APPLICATION_LAYER = "..application..";
    private static final String ADAPTER_IN_LAYER = "..infrastructure..adapter..in..";
    private static final String ADAPTER_OUT_LAYER = "..infrastructure..adapter..out..";

    /**
     * Domain must be pure: it only depends on itself or JDK (never on application or infrastructure).
     */
    @ArchTest
    static final ArchRule domain_must_be_isolated =
            classes().that().resideInAPackage(DOMAIN_LAYER)
                    .should().onlyDependOnClassesThat()
                    .resideOutsideOfPackages(APPLICATION_LAYER, "..infrastructure..");

    /**
     * Application layer can use Domain but must not depend on Infrastructure.
     */
    @ArchTest
    static final ArchRule application_must_not_depend_on_infrastructure =
            noClasses().that().resideInAPackage(APPLICATION_LAYER)
                    .should().dependOnClassesThat()
                    .resideInAPackage("..infrastructure..");

    /**
     * Inbound adapters (REST, messaging, etc.) must not depend on outbound adapters (persistence, external APIs).
     */
    @ArchTest
    static final ArchRule inbound_and_outbound_are_decoupled =
            noClasses().that().resideInAPackage(ADAPTER_IN_LAYER)
                    .should().dependOnClassesThat()
                    .resideInAPackage(ADAPTER_OUT_LAYER);

}