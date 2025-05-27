package com.videogame.platform.game.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.syntax.elements.ClassesShouldConjunction;

/** Simple naming conventions for adapters and mappers. */
@AnalyzeClasses(packages = "com.videogame.platform.game")
public class NamingConventionTest {

  @ArchTest
  static final ClassesShouldConjunction controllers_should_be_in_correct_package =
      classes()
          .that()
          .haveSimpleNameEndingWith("Controller")
          .should()
          .resideInAPackage("..adapter.in.rest..");

  @ArchTest
  static final ClassesShouldConjunction mappers_should_be_in_correct_package =
      classes()
          .that()
          .haveSimpleNameEndingWith("Mapper")
          .should()
          .resideInAnyPackage("..mapper..", "..mapper..");
}
