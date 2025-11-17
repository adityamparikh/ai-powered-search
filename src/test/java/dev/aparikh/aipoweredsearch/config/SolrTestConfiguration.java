package dev.aparikh.aipoweredsearch.config;

import org.springframework.boot.devtools.restart.RestartScope;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class SolrTestConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    @RestartScope
    SolrContainer solrContainer() {
        return new SolrContainer(DockerImageName.parse("solr:9.10.0"))
                .withEnv("SOLR_HEAP", "512m");
    }

    @Bean
    DynamicPropertyRegistrar dynamicPropertyRegistrar(SolrContainer solr) {
        // Provide base host:port only; SolrConfig will normalize and append "/solr/"
        return registry -> registry.add("solr.url", () -> "http://" + solr.getHost() + ":" + solr.getSolrPort());
    }

}
