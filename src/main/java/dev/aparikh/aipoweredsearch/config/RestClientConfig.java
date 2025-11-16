package dev.aparikh.aipoweredsearch.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

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
public class RestClientConfig {

    @Bean
    public RestClientCustomizer jdkRestClientCustomizer() {
        HttpClient httpClient = HttpClient.newBuilder().build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        return builder -> builder.requestFactory(requestFactory);
    }

    /**
     * Provide a RestClient.Builder bean and apply any registered RestClientCustomizers.
     * This guarantees a Builder is available in tests that don't load the full
     * auto-configuration, and that it uses the JDK HttpClient via our customizer.
     */
    @Bean
    public RestClient.Builder restClientBuilder(ObjectProvider<RestClientCustomizer> customizers) {
        RestClient.Builder builder = RestClient.builder();
        customizers.orderedStream().forEach(c -> c.customize(builder));
        return builder;
    }
}
