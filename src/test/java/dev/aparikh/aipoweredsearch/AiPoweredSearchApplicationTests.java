package dev.aparikh.aipoweredsearch;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

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
    void startsWithoutATypeSafeClientWhenNoKeyIsSet() {
        // The TypeSafe starter only activates when spring.ai.typesafe.api-key is set.
        assertThat(context.getBeanNamesForType(TypeSafeClient.class)).isEmpty();
    }

}
