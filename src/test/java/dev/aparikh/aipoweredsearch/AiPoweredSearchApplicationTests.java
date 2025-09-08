package dev.aparikh.aipoweredsearch;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.search.MockChatModelConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import({TestcontainersConfiguration.class, PostgresTestConfiguration.class, MockChatModelConfiguration.class})
@SpringBootTest
class AiPoweredSearchApplicationTests {

    @Test
    void contextLoads() {
    }

}
