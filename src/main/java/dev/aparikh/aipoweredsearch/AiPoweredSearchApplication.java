package dev.aparikh.aipoweredsearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
public class AiPoweredSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiPoweredSearchApplication.class, args);
    }

}
