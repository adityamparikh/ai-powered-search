import net.ltgt.gradle.errorprone.errorprone

val errorProneVersion = "2.50.0"
val nullawayVersion = "0.14.2"

plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.sonarqube") version "7.5.0.8588"
    jacoco
    id("net.ltgt.errorprone") version "5.1.1"
}

group = "dev.aparikh"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

extra["springAiVersion"] = "2.0.1"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    implementation("org.springframework.ai:spring-ai-openai")
    implementation("org.springframework.ai:spring-ai-starter-model-chat-memory-repository-jdbc")
    implementation("org.springframework.ai:spring-ai-vector-store")
    implementation("org.springframework.ai:spring-ai-vector-store-advisor")
    // Modular RAG: RetrievalAugmentationAdvisor, DocumentRetriever, DocumentJoiner
    implementation("org.springframework.ai:spring-ai-rag")
    // TypeSafe Jev: optional passage screening and reranking for RAG (W4). Its auto-configuration is
    // excluded in application.properties; RagPostProcessingConfig builds the client only when a Jev
    // stage is enabled and TYPESAFE_API_KEY is set.
    implementation("org.springaicommunity:spring-ai-starter-typesafe:0.3.0")
    implementation("org.springaicommunity:typesafe-spring-ai:0.3.0")
    implementation("org.postgresql:postgresql")
    // Apache Solr client. SolrJ 10 dropped Jetty in favour of the JDK HttpClient
    // (HttpJdkSolrClient), so no Jetty artifacts or version pinning are needed.
    implementation("org.apache.solr:solr-solrj:10.0.0")

    // Swagger UI / OpenAPI documentation
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")

    // Additional Solr dependencies
    implementation("commons-io:commons-io:2.22.0")
    implementation("org.apache.commons:commons-lang3:3.21.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.ai:spring-ai-spring-boot-testcontainers")
    testImplementation("org.springframework.ai:spring-ai-ollama")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-solr")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-ollama")
    testImplementation("org.testcontainers:testcontainers-grafana")
    testImplementation("org.awaitility:awaitility")
    testImplementation("io.micrometer:micrometer-observation-test")
    testImplementation("org.springframework.boot:spring-boot-devtools")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    errorprone("com.google.errorprone:error_prone_core:$errorProneVersion")
    errorprone("com.uber.nullaway:nullaway:$nullawayVersion")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
}

tasks.withType<Test> {
    maxHeapSize = "2g"
    // Forward -Drag.* to the test JVM: the RAG evaluation tests read their options from
    // system properties. Read through a provider so the values are tracked as build inputs.
    systemProperties(providers.systemPropertiesPrefixedBy("rag.").get())
}

// RagEvaluationIT (tag "rag-eval") makes real, billed model calls across ~50 cases, so `test`,
// and with it `./gradlew build`, never runs it. `./gradlew ragEval` runs it and nothing else.
tasks.test {
    useJUnitPlatform { excludeTags("rag-eval") }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register<Test>("ragEval") {
    description = "Runs the billed RAG evaluation harness (tests tagged rag-eval)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("rag-eval") }
    // Model output is not deterministic and runs are repeated on purpose; never skip as up to date.
    outputs.upToDateWhen { false }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
    options.errorprone.error("NullAway")
    options.errorprone.option("NullAway:JSpecifyMode", "true")
    options.errorprone.option("NullAway:AnnotatedPackages", "dev.aparikh.aipoweredsearch")
}

tasks.named<JavaCompile>("compileTestJava") {
    options.errorprone.disable("NullAway")
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

// SonarQube configuration
sonar {
    properties {
        property("sonar.projectKey", "ai-powered-search")
        property("sonar.projectName", "AI Powered Search")
        property("sonar.host.url", System.getenv("SONAR_HOST_URL") ?: "http://localhost:9000")
        property("sonar.token", System.getenv("SONAR_TOKEN") ?: "")
        property("sonar.java.source", "25")
        property("sonar.java.target", "25")
        property("sonar.sources", "src/main/java")
        property("sonar.tests", "src/test/java")
        property("sonar.java.binaries", "build/classes/java/main")
        property("sonar.java.test.binaries", "build/classes/java/test")
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco/test/jacocoTestReport.xml")
        property("sonar.exclusions", "**/*Application.java,**/*Config.java,**/model/**,**/dto/**")
    }
}
