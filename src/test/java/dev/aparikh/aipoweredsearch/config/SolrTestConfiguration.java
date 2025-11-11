package dev.aparikh.aipoweredsearch.config;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class SolrTestConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    public SolrContainer solrContainer(){
        return new SolrContainer(DockerImageName.parse("solr:slim"))
                .withEnv("SOLR_HEAP", "512m");
    }

    @Bean
    public DynamicPropertyRegistrar dynamicPropertyRegistrar(SolrContainer solr) {
        return registry -> registry.add("solr.url", () -> "http://" + solr.getHost() + ":" + solr.getSolrPort() + "/solr");
    }

}
