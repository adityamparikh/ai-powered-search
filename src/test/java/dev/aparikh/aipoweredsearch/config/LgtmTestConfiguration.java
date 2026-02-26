package dev.aparikh.aipoweredsearch.config;

import org.springframework.boot.devtools.restart.RestartScope;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.grafana.LgtmStackContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared test configuration for Grafana LGTM (Loki, Grafana, Tempo, Mimir) container.
 *
 * <p>This configuration provides a reusable LGTM container instance with @RestartScope
 * to avoid recreating the container for every test class.
 *
 * <p>The {@link ServiceConnection @ServiceConnection} annotation automatically configures:
 * <ul>
 *   <li>OtlpMetricsConnectionDetails (metrics export URL)</li>
 *   <li>OtlpTracingConnectionDetails (tracing endpoint)</li>
 *   <li>OtlpLoggingConnectionDetails (logging endpoint)</li>
 * </ul>
 *
 * <p>Exposed services:
 * <ul>
 *   <li>Grafana UI: port 3000</li>
 *   <li>Loki: port 3100</li>
 *   <li>Tempo: port 3200</li>
 *   <li>OpenTelemetry gRPC: port 4317</li>
 *   <li>OpenTelemetry HTTP: port 4318</li>
 *   <li>Prometheus: port 9090</li>
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
public class LgtmTestConfiguration {

    @Bean
    @ServiceConnection
    @RestartScope
    LgtmStackContainer lgtmStackContainer() {
        return new LgtmStackContainer(DockerImageName.parse("grafana/otel-lgtm:latest"))
                .withReuse(true);
    }
}
