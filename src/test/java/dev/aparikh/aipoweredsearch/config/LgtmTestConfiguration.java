package dev.aparikh.aipoweredsearch.config;

import java.time.Duration;
import org.springframework.boot.devtools.restart.RestartScope;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.grafana.LgtmStackContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared test configuration for Grafana LGTM (Loki, Grafana, Tempo, Mimir) container.
 *
 * <p>This configuration provides a reusable LGTM container instance with @RestartScope to avoid
 * recreating the container for every test class.
 *
 * <p>The {@link ServiceConnection @ServiceConnection} annotation automatically configures:
 *
 * <ul>
 *   <li>OtlpMetricsConnectionDetails (metrics export URL)
 *   <li>OtlpTracingConnectionDetails (tracing endpoint)
 *   <li>OtlpLoggingConnectionDetails (logging endpoint)
 * </ul>
 *
 * <p>Exposed services:
 *
 * <ul>
 *   <li>Grafana UI: port 3000
 *   <li>Loki: port 3100
 *   <li>Tempo: port 3200
 *   <li>OpenTelemetry gRPC: port 4317
 *   <li>OpenTelemetry HTTP: port 4318
 *   <li>Prometheus: port 9090
 * </ul>
 *
 * <p>The image is pinned rather than {@code latest} so a new upstream release cannot change startup
 * behaviour underneath the build. The startup timeout is raised because Grafana 13 (bundled since
 * otel-lgtm 0.34) takes ~50-55 seconds to report ready on a warm host, which sits right on top of
 * the Testcontainers default of 60 seconds and fails under build load.
 */
@TestConfiguration(proxyBeanMethods = false)
public class LgtmTestConfiguration {

  /** Keep in step with the {@code lgtm} service image in {@code docker-compose.yml}. */
  static final String LGTM_IMAGE = "grafana/otel-lgtm:0.35.0";

  @Bean
  @ServiceConnection
  @RestartScope
  LgtmStackContainer lgtmStackContainer() {
    return new LgtmStackContainer(DockerImageName.parse(LGTM_IMAGE))
        .withStartupTimeout(Duration.ofMinutes(3))
        .withReuse(true);
  }
}
