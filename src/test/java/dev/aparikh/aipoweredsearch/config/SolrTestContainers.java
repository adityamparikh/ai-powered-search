package dev.aparikh.aipoweredsearch.config;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.testcontainers.solr.SolrContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;

/**
 * Solr test fixtures shared by every Testcontainers-based test.
 *
 * <p>Two things here exist only because of the move to Solr 10:</p>
 * <ul>
 *   <li><strong>{@link #newSolrContainer()}</strong>: Testcontainers' {@link SolrContainer}
 *       (2.0.5) starts SolrCloud with a bare {@code -DzkRun}. Solr 10 parses that system
 *       property as a boolean and refuses to start on the empty value
 *       ({@code invalid boolean value: }). {@link Solr10Container} passes
 *       {@code -DzkRun=true} instead.</li>
 *   <li><strong>{@link #registerHybridHandler}</strong>: hybrid search goes through Solr's
 *       {@code /hybrid} handler ({@code solr.CombinedQuerySearchHandler}), which the project
 *       configset ({@code solr-config/conf}) declares but Solr's {@code _default} configset
 *       does not. Tests that build collections from {@code _default} add it with the Config
 *       API.</li>
 * </ul>
 */
public final class SolrTestContainers {

    /**
     * Solr image under test. The combined query (server-side RRF) feature shipped in Solr 10.1
     * and was backported to 9.11, so both are supported; CI runs the matrix by setting
     * {@code SOLR_TEST_IMAGE} (e.g. {@code solr:9.11.0}). Defaults to 10.1.
     */
    public static final DockerImageName SOLR_IMAGE = DockerImageName.parse(
            System.getenv().getOrDefault("SOLR_TEST_IMAGE", "solr:10.1.0"));

    private static final String HYBRID_HANDLER_CONFIG = """
            {
              "add-searchcomponent": {"name": "combined_query", "class": "solr.CombinedQueryComponent"},
              "add-requesthandler": {"name": "/hybrid", "class": "solr.CombinedQuerySearchHandler"}
            }
            """;

    private SolrTestContainers() {
    }

    /** A SolrCloud container (embedded ZooKeeper) running {@link #SOLR_IMAGE}. */
    public static SolrContainer newSolrContainer() {
        return new Solr10Container().withEnv("SOLR_HEAP", "512m");
    }

    /**
     * Registers the {@code /hybrid} combined-query handler on a collection created from
     * {@code _default}. Not needed (and not idempotent) for collections built from the
     * project configset, which already declares it.
     */
    public static void registerHybridHandler(SolrClient solrClient, String collection) throws Exception {
        GenericSolrRequest request = new GenericSolrRequest(SolrRequest.METHOD.POST, "/config")
                .setRequiresCollection(true)
                .withContent(HYBRID_HANDLER_CONFIG.getBytes(StandardCharsets.UTF_8), "application/json");
        solrClient.request(request, collection);
    }

    /** {@link SolrContainer} whose SolrCloud start command Solr 10 accepts. */
    static final class Solr10Container extends SolrContainer {

        Solr10Container() {
            super(SOLR_IMAGE);
        }

        @Override
        protected void configure() {
            super.configure();
            // super.configure() sets "-DzkRun --host localhost"; Solr 10 rejects the bare flag.
            setCommand("-DzkRun=true --host localhost");
        }
    }
}
