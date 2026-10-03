package dev.aparikh.aipoweredsearch;

import org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.resilience.annotation.EnableResilientMethods;

// TypeSafe's auto-configuration activates on the mere presence of spring.ai.typesafe.api-key, and an
// empty key (an unset TYPESAFE_API_KEY) fails startup. RagPostProcessingConfig builds the client
// itself, only when a Jev stage is enabled. Excluded here rather than with spring.autoconfigure.exclude,
// which a profile or SPRING_AUTOCONFIGURE_EXCLUDE would replace rather than add to.
@SpringBootApplication(exclude = TypeSafeAutoConfiguration.class)
@EnableResilientMethods
public class AiPoweredSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiPoweredSearchApplication.class, args);
    }

}
