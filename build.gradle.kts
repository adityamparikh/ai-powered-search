plugins {
    java
    id("org.springframework.boot") version "4.0.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.sonarqube") version "5.1.0.4882"
    jacoco
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

extra["springAiVersion"] = "2.0.0-M1"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    implementation("org.springframework.ai:spring-ai-openai") {
        // Exclude all Jetty artifacts to avoid conflicts; SolrJ will bring its own pinned Jetty 11
        exclude(group = "org.eclipse.jetty")
        exclude(group = "org.eclipse.jetty.http2")
    }
    implementation("org.springframework.ai:spring-ai-starter-model-chat-memory-repository-jdbc")
    implementation("org.springframework.ai:spring-ai-vector-store")
    implementation("org.springframework.ai:spring-ai-advisors-vector-store")
    implementation("org.postgresql:postgresql")
    // Apache Solr client with HTTP/2 support
    implementation("org.apache.solr:solr-solrj:9.10.0")
    // Jetty HTTP/2 client dependencies required for Http2SolrClient (Jetty 11)
    implementation("org.eclipse.jetty:jetty-client")
    implementation("org.eclipse.jetty.http2:http2-client")
    implementation("org.eclipse.jetty.http2:http2-http-client-transport")
    implementation("org.eclipse.jetty:jetty-io")
    implementation("org.eclipse.jetty:jetty-util")
    implementation("org.eclipse.jetty:jetty-alpn-java-client")

    // Swagger UI / OpenAPI documentation
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.0")

    // Additional Solr dependencies
    implementation("commons-io:commons-io:2.15.1")
    implementation("org.apache.commons:commons-lang3:3.18.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.ai:spring-ai-spring-boot-testcontainers")
    testImplementation("org.springframework.ai:spring-ai-ollama")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-solr")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-ollama")
    testImplementation("org.awaitility:awaitility:4.2.0")
    testImplementation("io.micrometer:micrometer-observation-test")
    testImplementation("org.springframework.boot:spring-boot-devtools")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
        // Pin Jetty to 11.x for SolrJ Http2SolrClient to avoid Jetty 12 conflicts
        mavenBom("org.eclipse.jetty:jetty-bom:11.0.24")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
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
        property("sonar.java.source", "21")
        property("sonar.java.target", "21")
        property("sonar.sources", "src/main/java")
        property("sonar.tests", "src/test/java")
        property("sonar.java.binaries", "build/classes/java/main")
        property("sonar.java.test.binaries", "build/classes/java/test")
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco/test/jacocoTestReport.xml")
        property("sonar.exclusions", "**/*Application.java,**/*Config.java,**/model/**,**/dto/**")
    }
}
