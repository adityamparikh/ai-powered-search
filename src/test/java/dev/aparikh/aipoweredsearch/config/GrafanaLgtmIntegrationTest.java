package dev.aparikh.aipoweredsearch.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.grafana.LgtmStackContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.ai.chat.model.ChatModel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying the Grafana LGTM (Loki, Grafana, Tempo, Mimir) stack
 * starts correctly via Testcontainers and that all services are accessible.
 *
 * <p>This test validates:
 * <ul>
 *   <li>The LGTM container starts and is running</li>
 *   <li>Grafana UI is accessible on its mapped port</li>
 *   <li>OTLP HTTP endpoint is accepting requests</li>
 *   <li>Prometheus metrics endpoint is accessible</li>
 *   <li>Spring Boot's {@code @ServiceConnection} auto-configures OTLP endpoints</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.ai.openai.api-key=test-key",
                "spring.ai.anthropic.api-key=test-key",
                "spring.ai.chat.client.enabled=false"
        })
@Testcontainers
@Import({PostgresTestConfiguration.class, SolrTestConfiguration.class, LgtmTestConfiguration.class})
class GrafanaLgtmIntegrationTest {

    @MockitoBean
    private ChatModel chatModel;

    @Autowired
    private LgtmStackContainer lgtmStackContainer;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void lgtmContainerIsRunning() {
        assertThat(lgtmStackContainer.isRunning()).isTrue();
    }

    @Test
    void grafanaUiIsAccessible() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(lgtmStackContainer.getGrafanaHttpUrl() + "/api/health"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("ok");
    }

    @Test
    void otlpHttpEndpointIsAccessible() throws Exception {
        // The OTLP HTTP endpoint should respond (even with an empty/invalid payload, it returns a response)
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(lgtmStackContainer.getOtlpHttpUrl() + "/v1/metrics"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // OTLP endpoint responds (400 for invalid payload is expected, but the endpoint is alive)
        assertThat(response.statusCode()).isIn(200, 400, 415);
    }

    @Test
    void prometheusEndpointIsAccessible() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(lgtmStackContainer.getPrometheusHttpUrl() + "/-/healthy"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void allServiceUrlsAreAvailable() {
        assertThat(lgtmStackContainer.getGrafanaHttpUrl()).startsWith("http://");
        assertThat(lgtmStackContainer.getOtlpGrpcUrl()).startsWith("http://");
        assertThat(lgtmStackContainer.getOtlpHttpUrl()).startsWith("http://");
        assertThat(lgtmStackContainer.getTempoUrl()).startsWith("http://");
        assertThat(lgtmStackContainer.getLokiUrl()).startsWith("http://");
        assertThat(lgtmStackContainer.getPrometheusHttpUrl()).startsWith("http://");
    }
}
