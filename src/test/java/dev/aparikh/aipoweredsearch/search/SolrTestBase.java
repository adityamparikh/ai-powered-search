package dev.aparikh.aipoweredsearch.search;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
public abstract class SolrTestBase {

    @Container
    protected static final SolrContainer solrContainer = new SolrContainer(DockerImageName.parse("solr:9.6"))
            .withEnv("SOLR_HEAP", "512m");

    @DynamicPropertySource
    static void solrProperties(DynamicPropertyRegistry registry) {
        registry.add("solr.url",
                () -> "http://" + solrContainer.getHost() + ":" + solrContainer.getSolrPort());
        registry.add("spring.ai.openai.api-key", () -> "test-key");
    }

    @Autowired
    protected SolrClient solrClient;

    protected final String TEST_COLLECTION = "test-collection-" + this.getClass().getSimpleName();

    @BeforeEach
    void setUpSolrCollection() throws Exception {
        // Create collection if it doesn't exist
        createCollection(TEST_COLLECTION);
        
        // Clear any existing documents
        clearCollection(TEST_COLLECTION);
        
        // Add test data
        populateTestData(TEST_COLLECTION);
    }

    protected void createCollection(String collection) throws Exception {
        try {
            // Create the collection using solr create_collection command
            var result = solrContainer.execInContainer("/opt/solr/bin/solr", "create_collection", 
                "-c", collection, "-d", "_default", "-shards", "1", "-replicationFactor", "1");
            
            System.out.println("Collection creation stdout: " + result.getStdout());
            System.out.println("Collection creation stderr: " + result.getStderr());
            
            // Check if creation was successful (might already exist)
            if (result.getExitCode() != 0 && !result.getStderr().contains("already exists")) {
                throw new RuntimeException("Failed to create collection: " + result.getStderr());
            }
            
            // Wait for collection to be ready
            Thread.sleep(3000);
            
        } catch (Exception e) {
            System.out.println("Exception during collection creation: " + e.getMessage());
            // Don't throw exception if collection already exists
            if (!e.getMessage().contains("already exists")) {
                throw e;
            }
        }
    }

    protected void clearCollection(String collection) throws Exception {
        try {
            // Test if collection exists first by doing a simple query
            solrClient.query(collection, new org.apache.solr.client.solrj.SolrQuery("*:*").setRows(0));
            
            // If we get here, collection exists, so clear it
            solrClient.deleteByQuery(collection, "*:*");
            solrClient.commit(collection);
            System.out.println("Successfully cleared collection: " + collection);
        } catch (Exception e) {
            System.out.println("Failed to clear collection (might not exist yet): " + e.getMessage());
        }
    }

    protected void populateTestData(String collection) throws Exception {
        try {
            // Test if collection is ready by doing a simple query
            solrClient.query(collection, new org.apache.solr.client.solrj.SolrQuery("*:*").setRows(0));
            
            // Add multiple test documents for comprehensive testing
            SolrInputDocument doc1 = new SolrInputDocument();
            doc1.addField("id", "1");
            doc1.addField("name", "Spring Boot Application");
            doc1.addField("description", "A sample Spring Boot application with Solr integration");
            doc1.addField("category", "framework");
            doc1.addField("tags", "java");
            doc1.addField("tags", "spring");

            SolrInputDocument doc2 = new SolrInputDocument();
            doc2.addField("id", "2");
            doc2.addField("name", "Apache Solr Guide");
            doc2.addField("description", "Complete guide to Apache Solr search engine");
            doc2.addField("category", "documentation");
            doc2.addField("tags", "solr");
            doc2.addField("tags", "search");

            SolrInputDocument doc3 = new SolrInputDocument();
            doc3.addField("id", "3");
            doc3.addField("name", "Microservices Architecture");
            doc3.addField("description", "Building scalable microservices with Spring Boot");
            doc3.addField("category", "architecture");
            doc3.addField("tags", "microservices");
            doc3.addField("tags", "spring");

            solrClient.add(collection, doc1);
            solrClient.add(collection, doc2);
            solrClient.add(collection, doc3);
            solrClient.commit(collection);
            
            // Wait for commit to complete
            Thread.sleep(1000);
            
            System.out.println("Successfully populated test data in collection: " + collection);
        } catch (Exception e) {
            System.out.println("Failed to populate test data: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
}