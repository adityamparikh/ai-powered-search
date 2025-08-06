package dev.aparikh.aipoweredsearch.search.repository;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class SolrTestConfiguration {

    @Container
    private static final SolrContainer solrContainer = new SolrContainer(DockerImageName.parse("solr:9.6"))
            .withEnv("SOLR_HEAP", "512m");

    static {
        solrContainer.start();
    }

    @Bean
    @Primary
    public SolrClient solrClient() {
        String solrUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getSolrPort() + "/solr";
        return new HttpSolrClient.Builder(solrUrl).build();
    }

    @Bean
    public SolrContainer solrContainer() {
        return solrContainer;
    }
}