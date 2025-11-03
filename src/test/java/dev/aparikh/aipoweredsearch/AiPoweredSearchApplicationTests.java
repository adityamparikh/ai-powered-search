package dev.aparikh.aipoweredsearch;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.search.MockChatModelConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Import({PostgresTestConfiguration.class, MockChatModelConfiguration.class})
@SpringBootTest
class AiPoweredSearchApplicationTests {

    @Container
    static final SolrContainer solrContainer = new SolrContainer(DockerImageName.parse("solr:9.6"))
            .withEnv("SOLR_HEAP", "512m");

    @DynamicPropertySource
    static void configureSolrProperties(DynamicPropertyRegistry registry) {
        String solrUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getSolrPort();
        registry.add("solr.url", () -> solrUrl);
        registry.add("spring.ai.openai.api-key", () -> "test-key");
    }

    @Test
    void contextLoads() {
    }

}
