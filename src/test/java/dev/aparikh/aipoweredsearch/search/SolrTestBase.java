package dev.aparikh.aipoweredsearch.search;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solr.SolrContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
public abstract class SolrTestBase {

    @Container
    protected static final SolrContainer solrContainer = new SolrContainer(DockerImageName.parse("solr:9.10.0"))
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
        // First, check if the collection already exists using Solr Admin API
        try {
            org.apache.solr.client.solrj.request.CollectionAdminRequest.List listReq =
                    new org.apache.solr.client.solrj.request.CollectionAdminRequest.List();
            var listResp = listReq.process(solrClient);
            @SuppressWarnings("unchecked")
            java.util.List<String> collections = (java.util.List<String>) listResp.getResponse().get("collections");
            if (collections != null && collections.contains(collection)) {
                System.out.println("Collection already exists, skipping creation: " + collection);
                // Still ensure schema is present (idempotent)
                addVectorFieldToSchema(collection);
                return;
            }
        } catch (Exception checkEx) {
            System.out.println("Could not list collections, will attempt to create: " + checkEx.getMessage());
        }

        try {
            // Create the collection using solr create_collection command
            var result = solrContainer.execInContainer("/opt/solr/bin/solr", "create_collection",
                "-c", collection, "-d", "_default", "-shards", "1", "-replicationFactor", "1");

            System.out.println("Collection creation stdout: " + result.getStdout());
            System.out.println("Collection creation stderr: " + result.getStderr());

            // Check if creation was successful (might already exist)
            if (result.getExitCode() != 0 && (result.getStderr() == null || !result.getStderr().contains("already exists"))) {
                throw new RuntimeException("Failed to create collection: " + result.getStderr());
            }

            // Wait for collection to be ready
            Thread.sleep(3000);

            // Add vector field to schema for hybrid search tests
            addVectorFieldToSchema(collection);

        } catch (Exception e) {
            System.out.println("Exception during collection creation: " + e.getMessage());
            // Don't throw exception if collection already exists
            if (e.getMessage() == null || !e.getMessage().contains("already exists")) {
                throw e;
            }
        }
    }

    protected void addVectorFieldToSchema(String collection) {
        try {
            // Add vector field type using exec command
            // Create field type first
            var fieldTypeResult = solrContainer.execInContainer(
                    "curl", "-X", "POST",
                    "-H", "Content-type:application/json",
                    "--data-binary",
                    """
                            {"add-field-type":{"name":"knn_vector","class":"solr.DenseVectorField","vectorDimension":5,"similarityFunction":"cosine"}}
                            """.strip(),
                    "http://localhost:8983/solr/" + collection + "/schema"
            );

            System.out.println("Field type creation result: " + fieldTypeResult.getStdout());

            // Add field
            var fieldResult = solrContainer.execInContainer(
                    "curl", "-X", "POST",
                    "-H", "Content-type:application/json",
                    "--data-binary",
                    """
                            {"add-field":{"name":"vector","type":"knn_vector","indexed":true,"stored":true}}
                            """.strip(),
                    "http://localhost:8983/solr/" + collection + "/schema"
            );

            System.out.println("Vector field creation result: " + fieldResult.getStdout());

            // Wait for schema changes to propagate
            Thread.sleep(2000);

        } catch (Exception e) {
            // Silently ignore if field already exists - test collections may already have it
            System.out.println("Could not add vector field (may already exist): " + e.getMessage());
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
            doc1.addField("vector", java.util.List.of(0.1f, 0.2f, 0.3f, 0.4f, 0.5f));

            SolrInputDocument doc2 = new SolrInputDocument();
            doc2.addField("id", "2");
            doc2.addField("name", "Apache Solr Guide");
            doc2.addField("description", "Complete guide to Apache Solr search engine");
            doc2.addField("category", "documentation");
            doc2.addField("tags", "solr");
            doc2.addField("tags", "search");
            doc2.addField("vector", java.util.List.of(0.2f, 0.3f, 0.4f, 0.5f, 0.6f));

            SolrInputDocument doc3 = new SolrInputDocument();
            doc3.addField("id", "3");
            doc3.addField("name", "Microservices Architecture");
            doc3.addField("description", "Building scalable microservices with Spring Boot");
            doc3.addField("category", "architecture");
            doc3.addField("tags", "microservices");
            doc3.addField("tags", "spring");
            doc3.addField("vector", java.util.List.of(0.3f, 0.4f, 0.5f, 0.6f, 0.7f));

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