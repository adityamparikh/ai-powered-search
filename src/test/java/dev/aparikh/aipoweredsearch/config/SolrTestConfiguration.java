package dev.aparikh.aipoweredsearch.config;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class SolrTestConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    public SolrContainer solrContainer(){
        return new SolrContainer(DockerImageName.parse("solr:9.6"))
                .withEnv("SOLR_HEAP", "512m");
    }

    @Bean
    @Primary
    public SolrClient solrClient(SolrContainer solrContainer) {
        // Use HttpSolrClient for tests pointing to the test container
        String solrUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getSolrPort() + "/solr";
        return new HttpSolrClient.Builder(solrUrl).build();
    }
}
