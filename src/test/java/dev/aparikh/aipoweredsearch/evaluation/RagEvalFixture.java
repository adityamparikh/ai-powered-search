package dev.aparikh.aipoweredsearch.evaluation;

import dev.aparikh.aipoweredsearch.indexing.IndexService;
import dev.aparikh.aipoweredsearch.indexing.model.BatchIndexRequest;
import dev.aparikh.aipoweredsearch.indexing.model.IndexRequest;
import dev.aparikh.aipoweredsearch.indexing.model.IndexResponse;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.testcontainers.containers.Container;
import org.testcontainers.solr.SolrContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Builds the evaluation collection the same way production does.
 *
 * <p>Two details matter for the numbers to mean anything:</p>
 * <ul>
 *   <li><strong>The project configset, not {@code _default}.</strong> Solr's {@code _default}
 *       configset leaves {@code copyField * -> _text_} commented out, so the BM25 leg (which
 *       queries {@code _text_}) silently returns nothing and "hybrid" retrieval degrades to
 *       vector-only. {@code solr-config/conf} copies {@code content} into {@code _text_}.</li>
 *   <li><strong>Typed filter fields</strong> (W0 finding A7). Metadata indexed through
 *       {@code /api/v1/index} lands in {@code metadata_*}, which the configset types as
 *       multi-valued {@code text_general}: ranges there compare tokens lexically and author
 *       names are tokenised. Explicit fields win over dynamic ones, so {@code metadata_author}
 *       ({@code string}), {@code metadata_price} ({@code pdouble}) and {@code metadata_year}
 *       ({@code pint}) are declared before indexing.</li>
 *   <li><strong>One segment, deterministically.</strong> The configset soft-commits every 3s.
 *       Under load, the fixture's single batch can straddle a soft commit and land in two
 *       segments. HNSW kNN is approximate per segment, so the vector leg's order would then
 *       vary from run to run. Fixture collections switch automatic commits off, so the batch
 *       and its one explicit commit always produce exactly one segment.</li>
 * </ul>
 */
public final class RagEvalFixture {

    /** Name under which {@code solr-config/conf} is uploaded to ZooKeeper. */
    public static final String CONFIGSET = "rag-eval-config";

    /** Explicit, filterable metadata fields and their Solr types (W0 finding A7). */
    public static final Map<String, String> TYPED_FIELDS = Map.of(
            "metadata_author", "string",
            "metadata_price", "pdouble",
            "metadata_year", "pint");

    private static final String CONTAINER_CONFIG_DIR = "/tmp/rag-eval-configset";
    private static final String EMBEDDED_ZK = "localhost:9983";

    private RagEvalFixture() {
    }

    /**
     * Uploads {@code solr-config/conf} into the container's embedded ZooKeeper.
     */
    public static void uploadConfigSet(SolrContainer solr) throws Exception {
        Path conf = Path.of("solr-config", "conf").toAbsolutePath();
        if (!Files.isDirectory(conf)) {
            throw new IllegalStateException("Project configset not found at " + conf);
        }
        solr.copyFileToContainer(MountableFile.forHostPath(conf), CONTAINER_CONFIG_DIR + "/conf");
        Container.ExecResult result = solr.execInContainer(
                "/opt/solr/bin/solr", "zk", "upconfig", "-n", CONFIGSET, "-d", CONTAINER_CONFIG_DIR, "-z", EMBEDDED_ZK);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("upconfig failed: " + result.getStdout() + result.getStderr());
        }
    }

    /**
     * (Re)creates the collection from the uploaded configset and declares the typed fields.
     */
    public static void createCollection(SolrClient client, String collection) throws Exception {
        List<String> existing = CollectionAdminRequest.listCollections(client);
        if (existing.contains(collection)) {
            CollectionAdminRequest.deleteCollection(collection).process(client);
        }
        CollectionAdminRequest.createCollection(collection, CONFIGSET, 1, 1)
                // Core properties override the ${solr.auto*Commit.maxTime} defaults in solrconfig.xml.
                .withProperty("solr.autoSoftCommit.maxTime", "-1")
                .withProperty("solr.autoCommit.maxTime", "-1")
                .process(client);
        for (Map.Entry<String, String> field : TYPED_FIELDS.entrySet()) {
            new SchemaRequest.AddField(Map.of(
                    "name", field.getKey(),
                    "type", field.getValue(),
                    "indexed", true,
                    "stored", true,
                    "multiValued", false)).process(client, collection);
        }
    }

    /**
     * Indexes the fixture through {@link IndexService}, i.e. through the production path
     * (embedding via the configured model, {@code metadata_} prefixing by the vector store).
     */
    public static void index(IndexService indexService, SolrClient client, String collection,
                             List<RagEvalData.Book> books) throws Exception {
        List<IndexRequest> requests = books.stream()
                .map(book -> new IndexRequest(book.id(), book.content(), book.metadata()))
                .toList();
        IndexResponse response = indexService.indexDocuments(collection, new BatchIndexRequest(requests));
        if (response.failed() > 0) {
            throw new IllegalStateException("Fixture indexing failed: " + response.message());
        }
        client.commit(collection);
        long found = client.query(collection, new SolrQuery("*:*")).getResults().getNumFound();
        if (found != books.size()) {
            throw new IllegalStateException("Expected " + books.size() + " fixture documents, found " + found);
        }
    }
}
