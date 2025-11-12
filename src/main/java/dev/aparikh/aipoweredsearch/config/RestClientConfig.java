package dev.aparikh.aipoweredsearch.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;

/**
 * Ensure that Spring's RestClient uses JDK HttpClient and not Jetty.
 *
 * <p>We depend on Jetty only for SolrJ's Http2SolrClient. To avoid
 * Jetty interfering with RestClient/RestTemplate/RestController,
 * we explicitly customize the RestClient to use the JDK HttpClient
 * based request factory.
 */
@Configuration
class RestClientConfig {

    @Bean
    RestClientCustomizer jdkRestClientCustomizer() {
        HttpClient httpClient = HttpClient.newBuilder().build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        return builder -> builder.requestFactory(requestFactory);
    }
}
