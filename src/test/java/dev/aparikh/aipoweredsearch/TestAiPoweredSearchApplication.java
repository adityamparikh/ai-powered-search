package dev.aparikh.aipoweredsearch;

import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import org.springframework.boot.SpringApplication;

public class TestAiPoweredSearchApplication {

    static void main(String[] args) {
        SpringApplication.from(AiPoweredSearchApplication::main).with(SolrTestConfiguration.class).run(args);
    }

}
