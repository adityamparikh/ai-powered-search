package dev.aparikh.aipoweredsearch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import dev.aparikh.aipoweredsearch.search.rag.RagPostProcessors;
import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@Import({PostgresTestConfiguration.class, SolrTestConfiguration.class})
@SpringBootTest
class AiPoweredSearchApplicationTests {


    @DynamicPropertySource
    static void configureSolrProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.api-key", () -> "test-key");
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
    }

    @Test
    void startsWithoutTypeSafeAndWithoutJevByDefault() {
        // application.properties maps spring.ai.typesafe.api-key=${TYPESAFE_API_KEY:}, which is empty
        // here. The starter's auto-configuration would fail on that (W0 finding A2), so it is excluded,
        // and no Jev stage is assembled unless enabled.
        assertThat(context.getBeanNamesForType(TypeSafeClient.class)).isEmpty();
        assertThat(context.getBean(RagPostProcessors.class).processors()).hasSize(1);
    }

}
