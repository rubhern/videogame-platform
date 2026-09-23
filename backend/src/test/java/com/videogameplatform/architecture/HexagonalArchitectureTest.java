package com.videogameplatform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "com.videogameplatform",
        importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule DOMAIN_IS_FRAMEWORK_AND_INFRASTRUCTURE_INDEPENDENT =
            noClasses()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta..",
                            "java.sql..",
                            "..application..",
                            "..adapter..",
                            "..api..",
                            "..platform..");

    @ArchTest
    static final ArchRule APPLICATION_DOES_NOT_DEPEND_ON_ADAPTERS_OR_FRAMEWORKS =
            noClasses()
                    .that()
                    .resideInAPackage("..application..")
                    .and()
                    .doNotHaveSimpleName("package-info")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta..",
                            "java.sql..",
                            "..adapter..",
                            "..api..",
                            "..platform..");

    @ArchTest
    static final ArchRule ADAPTERS_DO_NOT_DEPEND_ON_APPLICATION_INTERNALS =
            noClasses()
                    .that()
                    .resideInAnyPackage("..adapter..", "..api.delivery..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..application.internal..", "..application..internal..");

    @ArchTest
    static final ArchRule DOMAIN_DOES_NOT_USE_BOUNDARY_MODELS =
            noClasses()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "..api.model..",
                            "..adapter.persistence.model..",
                            "..adapter.provider..model..");

    @ArchTest
    static final ArchRule API_MODELS_DO_NOT_USE_OUTBOUND_MODELS =
            noClasses()
                    .that()
                    .resideInAPackage("..api.model..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "..adapter.persistence.model..", "..adapter.provider..model..");

    @ArchTest
    static final ArchRule OUTBOUND_MODELS_DO_NOT_USE_API_MODELS =
            noClasses()
                    .that()
                    .resideInAnyPackage(
                            "..adapter.persistence.model..", "..adapter.provider..model..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..api.model..");

    @ArchTest
    static final ArchRule GENERATED_OPENAPI_TYPES_STAY_INSIDE_HTTP_DELIVERY =
            noClasses()
                    .that()
                    .resideOutsideOfPackages("..api.delivery..", "..api.generated..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..api.generated..");
}
