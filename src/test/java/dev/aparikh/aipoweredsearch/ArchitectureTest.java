package dev.aparikh.aipoweredsearch;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import dev.aparikh.aipoweredsearch.search.SearchRepository;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Turns the package-by-feature structure and the design decisions in {@code CLAUDE.md} into build
 * failures, so they hold for every change, whoever (or whatever) wrote it.
 *
 * <p>Runs without containers or API keys: ArchUnit reads compiled classes only.
 */
@AnalyzeClasses(
    packages = "dev.aparikh.aipoweredsearch",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  private static final String ROOT = "dev.aparikh.aipoweredsearch";

  /** Package-by-feature only works while the features do not tangle. */
  @ArchTest
  static final ArchRule packagesAreFreeOfCycles =
      slices().matching(ROOT + ".(*)..").should().beFreeOfCycles();

  @ArchTest
  static final ArchRule searchDoesNotDependOnIndexing =
      noClasses()
          .that()
          .resideInAPackage(ROOT + ".search..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage(ROOT + ".indexing..")
          .because(
              "search and indexing are separate features; share code through solr or embedding");

  @ArchTest
  static final ArchRule indexingDoesNotDependOnSearch =
      noClasses()
          .that()
          .resideInAPackage(ROOT + ".indexing..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage(ROOT + ".search..")
          .because(
              "search and indexing are separate features; share code through solr or embedding");

  /**
   * {@code solr} and {@code embedding} are the shared infrastructure the features build on. They
   * must not reach back up into a feature or into the wiring in {@code config}.
   */
  @ArchTest
  static final ArchRule infrastructureDoesNotDependOnFeatures =
      noClasses()
          .that()
          .resideInAnyPackage(ROOT + ".solr..", ROOT + ".embedding..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(ROOT + ".search..", ROOT + ".indexing..", ROOT + ".config..")
          .because("solr and embedding are shared infrastructure, below the features");

  /** Request and response types carry data; they must not call into the application. */
  @ArchTest
  static final ArchRule modelsDependOnlyOnModels =
      noClasses()
          .that()
          .resideInAPackage(ROOT + "..model..")
          .should()
          .dependOnClassesThat(
              describe(
                  "application classes outside a model package",
                  c ->
                      c.getPackageName().startsWith(ROOT)
                          && !c.getPackageName().contains(".model")))
          .because("model packages hold request and response types only");

  /**
   * SolrJ stays behind the repository and vector store, so the query shape (including the
   * combined-query /hybrid request) is decided in one place.
   */
  @ArchTest
  static final ArchRule solrJStaysBehindTheRepository =
      noClasses()
          .that()
          .resideOutsideOfPackages(ROOT + ".solr..", ROOT + ".config..")
          .and()
          .doNotBelongToAnyOf(SearchRepository.class)
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.apache.solr..")
          .because("controllers and services go through SearchRepository or the vector store");

  @ArchTest
  static final ArchRule controllersDoNotUseTheRepositoryDirectly =
      noClasses()
          .that()
          .haveSimpleNameEndingWith("Controller")
          .should()
          .dependOnClassesThat()
          .haveSimpleNameEndingWith("Repository")
          .because("controllers call services; SearchRepository is a service's collaborator");

  /**
   * CLAUDE.md: HybridDocumentRetriever calls the repository directly, deliberately skipping
   * SearchService's Claude query-generation step, which is not worth its latency on a RAG turn.
   */
  @ArchTest
  static final ArchRule ragRetrievalSkipsQueryGeneration =
      noClasses()
          .that()
          .haveFullyQualifiedName(ROOT + ".search.HybridDocumentRetriever")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName(ROOT + ".search.SearchService")
          .because("RAG retrieval must not pay for Claude query generation (see CLAUDE.md)");

  /** Constructor injection keeps collaborators visible to the Mockito unit tests. */
  @ArchTest
  static final ArchRule noFieldInjection =
      fields()
          .should()
          .notBeAnnotatedWith(Autowired.class)
          .because("inject through constructors so unit tests can pass mocks");
}
