package dev.aparikh.aipoweredsearch;

import org.springframework.boot.SpringApplication;

public class TestAiPoweredSearchApplication {

    public static void main(String[] args) {
        SpringApplication.from(AiPoweredSearchApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
