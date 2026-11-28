package dev.aparikh.aipoweredsearch;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Enforces the application's module boundaries with Spring Modulith.
 *
 * <p>Every direct sub-package of {@code dev.aparikh.aipoweredsearch} is an application module. A
 * module's API is the public types in its base package plus any package it exposes with
 * {@code @NamedInterface}; everything in its other sub-packages is internal. Each module's {@code
 * package-info.java} lists the modules it may depend on in {@code @ApplicationModule(
 * allowedDependencies = ...)}.
 *
 * <p>If this test fails, fix the dependency, not the test: move the code to the module that owns
 * it, or expose a deliberate API. Widening {@code allowedDependencies} is an architecture change
 * and belongs in the PR description.
 *
 * <p>No Spring context is started, so this runs in well under a second as part of {@code ./gradlew
 * build}.
 */
class ModularityTests {

  private static final ApplicationModules MODULES =
      ApplicationModules.of(AiPoweredSearchApplication.class);

  @Test
  void modulesRespectTheirBoundaries() {
    MODULES.verify();
  }

  /**
   * Writes the module diagrams and per-module canvases to {@code build/spring-modulith-docs}, so
   * the current module map can be read without reverse-engineering the imports.
   */
  @Test
  void writesModuleDocumentation() {
    new Documenter(MODULES).writeDocumentation();
  }
}
