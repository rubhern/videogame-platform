package com.videogameplatform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The external provider must stay unreachable from anything that serves a visitor.
 *
 * <p>"No user request depends on a live provider call" is easy to state and easy to lose to one
 * convenient import, so it is a fitness function rather than a convention: HTTP delivery cannot see
 * the synchronization use case or the provider adapter, and the local read paths cannot either.
 */
@AnalyzeClasses(
        packages = "com.videogameplatform",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ProviderBoundaryTest {

    @ArchTest
    static final ArchRule HTTP_DELIVERY_CANNOT_REACH_THE_PROVIDER_OR_SYNCHRONIZATION =
            noClasses()
                    .that()
                    .resideInAPackage("..api..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "..catalogue.application.synchronization..",
                            "..catalogue.adapter.provider..");

    @ArchTest
    static final ArchRule LOCAL_READ_PATHS_CANNOT_REACH_THE_PROVIDER_OR_SYNCHRONIZATION =
            noClasses()
                    .that()
                    .resideInAnyPackage(
                            "..catalogue.application.releases..",
                            "..catalogue.application.search..",
                            "..catalogue.adapter.persistence.releases..",
                            "..catalogue.adapter.persistence.search..",
                            "..ratings..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "..catalogue.application.synchronization..",
                            "..catalogue.adapter.provider..");

    @ArchTest
    static final ArchRule ONLY_THE_PROVIDER_ADAPTER_USES_PROVIDER_TRANSPORT_MODELS =
            noClasses()
                    .that()
                    .resideOutsideOfPackage("..adapter.provider..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..adapter.provider..model..");

    @ArchTest
    static final ArchRule PROVIDER_ACCESS_IS_COMPOSED_ONLY_WHERE_IT_IS_OWNED =
            noClasses()
                    .that()
                    .resideOutsideOfPackages(
                            "..catalogue.application.synchronization..",
                            "..catalogue.adapter.provider..",
                            "..catalogue.adapter.operator..",
                            "..catalogue.configuration..")
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName(
                            "com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort");
}
