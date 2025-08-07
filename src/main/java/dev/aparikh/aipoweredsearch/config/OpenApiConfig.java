package dev.aparikh.aipoweredsearch.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI aiPoweredSearchOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("AI-Powered Search API")
                        .description("API for AI-enhanced search functionality using Solr and Anthropic models")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("AI-Powered Search Team")
                                .url("https://github.com/aparikh/ai-powered-search"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")));
    }
}