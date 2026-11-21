---
name: upgrade-dependency
description: Upgrade a dependency, plugin, BOM or the Gradle wrapper in ai-powered-search (Spring Boot, Spring AI, SolrJ, Testcontainers, TypeSafe, Error Prone, ...) and prove nothing regressed, including licences and Spring AI advisor behaviour. Use for "bump X", "upgrade Spring AI", "review this Dependabot PR", "update dependencies".
---

# Upgrade a dependency

Versions live in `build.gradle.kts`: plugin versions in `plugins {}`, `springAiVersion` (Spring AI
BOM), and explicit versions for non-BOM libraries (`solr-solrj`, `springdoc`, `commons-*`,
`errorProneVersion`, `nullawayVersion`, `typesafeVersion`). Most runtime libraries take their
version from the Spring Boot or Spring AI BOM: upgrade the BOM, do not pin a managed library.

## Steps

1. Read the release notes between the current and target version and list breaking changes that
   touch this code (search for the APIs with `grep -rn`).
2. Change the version on a branch. One logical upgrade per PR (a BOM and the libraries it moves
   together is one).
3. Prove it:

   ```bash
   ./gradlew spotlessApply
   ./gradlew verify        # build + checkLicense
   ./gradlew dependencies --configuration runtimeClasspath | grep -i <artifact>   # resolved version
   ```

   `checkLicense` fails if the new version (or a new transitive) has a licence outside
   `config/allowed-licenses.json`. Do not edit that file to make it pass; report the licence to the
   maintainer.
4. Spring AI upgrades: `RetrievalAugmentationAdvisorContractTest` and `RagAdvisorOrderingIT` pin the
   advisor behaviour `/ask` relies on (ordering, what post-processors receive, empty-context
   handling). If they fail, the upgrade changed behaviour: fix the code, never the assertion,
   unless the maintainer agrees. A quality check needs `ragEval` (billed; use the `run-rag-eval`
   skill and ask first).
5. SolrJ or Solr image upgrades: run the Solr matrix locally for both versions:
   `SOLR_TEST_IMAGE=solr:9.11.0 ./gradlew test --tests "*SolrHybridSearchIT"`, and keep
   `docker-compose.yml`, `SolrTestContainers` and CI's `hybrid-search-solr.yml` matrix in step.
   The LGTM image tag must match between `docker-compose.yml` and `LgtmTestConfiguration`.
6. Update the versions named in `CLAUDE.md` (first paragraph) and `README.md` if they changed.

The Gradle wrapper: `./gradlew wrapper --gradle-version <v> --gradle-distribution-sha256-sum <sum>`,
then `./gradlew verify`.
