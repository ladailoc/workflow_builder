package com.fpt.workflow;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.fpt.workflow", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureConventionsTest {

  @ArchTest
  static final ArchRule featurePackagesMustRemainAcyclic =
      slices().matching("com.fpt.workflow.(*)..").should().beFreeOfCycles();
}
