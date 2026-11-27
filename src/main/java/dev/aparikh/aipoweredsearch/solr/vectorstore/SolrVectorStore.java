package dev.aparikh.aipoweredsearch.solr.vectorstore;

import dev.aparikh.aipoweredsearch.embedding.VectorFormatUtils;
import dev.aparikh.aipoweredsearch.solr.SolrQueryUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.AbstractVectorStoreBuilder;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.observation.AbstractObservationVectorStore;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;
import org.springframework.util.Assert;

/**
 * Apache Solr vector store implementation for Spring AI document storage and retrieval.
 *
 * <p>This class provides a full-featured vector store using Apache Solr's dense vector search
 * capabilities (Solr 9.0+). It enables semantic search by storing document embeddings and
 * performing similarity-based retrieval using KNN (K-Nearest Neighbors) algorithms.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Automatic embedding generation using OpenAI's text-embedding-3-small model
 *   <li>HNSW (Hierarchical Navigable Small World) algorithm for efficient similarity search
 *   <li>Cosine similarity metric for semantic matching
 *   <li>Metadata filtering with Spring AI filter expressions
 *   <li>Observability support via Micrometer metrics
 *   <li>Batch document processing for improved performance
 * </ul>
 *
 * <h3>Solr Schema Requirements:</h3>
 *
 * <p>The Solr collection must have the following fields defined:
 *
 * <pre>{@code
 * <field name="id" type="string" indexed="true" stored="true" required="true"/>
 * <field name="content" type="text_general" indexed="true" stored="true"/>
 * <field name="vector" type="knn_vector_1536" indexed="true" stored="true"/>
 *
 * <fieldType name="knn_vector_1536" class="solr.DenseVectorField"
 *            vectorDimension="1536"
 *            similarityFunction="cosine"
 *            knnAlgorithm="hnsw"/>
 * }</pre>
 *
 * <p>Metadata fields are stored with the prefix "metadata_" and support dynamic field types.
 *
 * <h3>Usage Example:</h3>
 *
 * <pre>{@code
 * SolrVectorStore vectorStore = SolrVectorStore.builder(solrClient, "products", embeddingModel)
 *     .options(SolrVectorStoreOptions.defaults())
 *     .build();
 *
 * // Add documents
 * Document doc = Document.builder()
 *     .id("prod-123")
 *     .text("High-performance running shoes with cushioned sole")
 *     .metadata(Map.of("category", "footwear", "price", 99.99))
 *     .build();
 * vectorStore.add(List.of(doc));
 *
 * // Semantic search
 * SearchRequest request = SearchRequest.builder()
 *     .query("comfortable athletic shoes")
 *     .topK(10)
 *     .filterExpression("price < 150")
 *     .build();
 * List<Document> results = vectorStore.similaritySearch(request);
 * }</pre>
 *
 * @author Aditya Parikh
 * @since 1.0.0
 * @see AbstractObservationVectorStore
 * @see SolrVectorStoreOptions
 */
public class SolrVectorStore extends AbstractObservationVectorStore {
  private static final String SCORE_FIELD = "score";
  private static final String EMBEDDING_METADATA_KEY = "embedding";
  private static final String YEAR_METADATA_KEY = "year";
  private static final String SELECT_HANDLER = "/select";
  private static final String QUERY_TYPE_PARAM = "qt";
  private static final String WILDCARD = "*";

  /** Upper bound, in milliseconds, before Solr commits an add or delete on its own. */
  private static final int COMMIT_WITHIN_MS = 1000;

  private static final Logger log = LoggerFactory.getLogger(SolrVectorStore.class);

  private final SolrClient solrClient;
  private final String collection;
  private final SolrVectorStoreOptions options;
  private final boolean initializeSchema;

  /** Protected constructor - use Builder to create instances. */
  protected SolrVectorStore(Builder builder) {
    super(builder);

    Assert.notNull(builder.solrClient, "SolrClient must not be null");
    Assert.notNull(builder.collection, "Collection name must not be null");

    this.solrClient = builder.solrClient;
    this.collection = builder.collection;
    this.options = builder.options != null ? builder.options : SolrVectorStoreOptions.defaults();
    this.initializeSchema = builder.initializeSchema;

    if (this.initializeSchema) {
      initializeSchema();
    }
  }

  /**
   * Creates a builder for SolrVectorStore.
   *
   * @param solrClient the Solr client
   * @param collection the Solr collection name
   * @param embeddingModel the embedding model
   * @return builder instance
   */
  public static Builder builder(
      SolrClient solrClient, String collection, EmbeddingModel embeddingModel) {
    return new Builder(solrClient, collection, embeddingModel);
  }

  /**
   * Adds documents with embeddings to the vector store. Embeddings are generated automatically if
   * not present in metadata.
   *
   * @param documents list of documents to add
   */
  @Override
  public void doAdd(List<Document> documents) {
    embedDocumentsMissingEmbeddings(documents);
    List<SolrInputDocument> solrDocs = documents.stream().map(this::toSolrDocument).toList();
    try {
      UpdateResponse response = solrClient.add(collection, solrDocs, COMMIT_WITHIN_MS);
      // commitWithin alone leaves a window where the documents are not yet searchable; the
      // explicit commit makes them visible to the very next query.
      solrClient.commit(collection);

      log.debug(
          "Added {} documents to Solr collection '{}', status: {}",
          documents.size(),
          collection,
          response.getStatus());
    } catch (SolrServerException | IOException e) {
      throw new IllegalStateException("Failed to add documents to Solr", e);
    }
  }

  /** Embeds, in one batch call, the documents that carry no embedding in their metadata. */
  private void embedDocumentsMissingEmbeddings(List<Document> documents) {
    List<Document> documentsWithoutEmbeddings =
        documents.stream().filter(doc -> embeddingOf(doc).isEmpty()).toList();
    if (documentsWithoutEmbeddings.isEmpty()) {
      return;
    }

    List<String> texts = documentsWithoutEmbeddings.stream().map(Document::getText).toList();
    EmbeddingResponse embeddingResponse = this.embeddingModel.embedForResponse(texts);
    if (embeddingResponse.getResults().size() != documentsWithoutEmbeddings.size()) {
      throw new IllegalStateException(
          "Expected "
              + documentsWithoutEmbeddings.size()
              + " embeddings but got "
              + embeddingResponse.getResults().size());
    }

    for (int i = 0; i < documentsWithoutEmbeddings.size(); i++) {
      float[] embedding = embeddingResponse.getResults().get(i).getOutput();
      documentsWithoutEmbeddings.get(i).getMetadata().put(EMBEDDING_METADATA_KEY, embedding);
    }
  }

  private static Optional<float[]> embeddingOf(Document document) {
    return document.getMetadata().get(EMBEDDING_METADATA_KEY) instanceof float[] embedding
            && embedding.length > 0
        ? Optional.of(embedding)
        : Optional.empty();
  }

  /**
   * Deletes documents by ID.
   *
   * @param idList list of document IDs to delete
   */
  @Override
  public void doDelete(List<String> idList) {
    try {
      UpdateResponse response = solrClient.deleteById(collection, idList, COMMIT_WITHIN_MS);

      log.debug(
          "Deleted {} documents from Solr collection '{}', status: {}",
          idList.size(),
          collection,
          response.getStatus());
    } catch (SolrServerException | IOException e) {
      throw new IllegalStateException("Failed to delete documents from Solr", e);
    }
  }

  /**
   * Performs similarity search using vector KNN.
   *
   * @param request the search request with query, filters, and top-k
   * @return list of similar documents
   */
  @Override
  public List<Document> doSimilaritySearch(SearchRequest request) {
    if (request.getQuery().isEmpty()) {
      throw new IllegalArgumentException("Search query cannot be null or empty");
    }
    SolrQuery query = buildKnnQuery(request, embedQuery(request.getQuery()));
    try {
      // POST avoids "URI Too Long" errors with large vector embeddings.
      QueryResponse response = solrClient.query(collection, query, SolrRequest.METHOD.POST);
      List<SolrDocument> results = response.getResults();

      log.debug(
          "Similarity search in collection '{}' returned {} results", collection, results.size());

      // "score" is a pseudo-field that exists only at query time, so the similarity threshold
      // cannot be a Solr filter and is applied to the results instead.
      double threshold = request.getSimilarityThreshold();
      return results.stream()
          .map(this::toDocument)
          .flatMap(Optional::stream)
          .filter(doc -> meetsThreshold(doc, threshold))
          .toList();
    } catch (SolrServerException | IOException e) {
      throw new IllegalStateException("Failed to perform similarity search in Solr", e);
    }
  }

  private float[] embedQuery(String queryText) {
    EmbeddingResponse embeddingResponse =
        this.embeddingModel.embedForResponse(Collections.singletonList(queryText));
    if (embeddingResponse.getResults().isEmpty()) {
      throw new IllegalStateException("Failed to generate embedding for query");
    }
    return embeddingResponse.getResults().getFirst().getOutput();
  }

  private SolrQuery buildKnnQuery(SearchRequest request, float[] queryEmbedding) {
    String vectorString = VectorFormatUtils.formatVectorForSolr(queryEmbedding);
    SolrQuery query =
        new SolrQuery(
            SolrQueryUtils.buildKnnQuery(
                options.vectorFieldName(), request.getTopK(), vectorString));
    if (request.getTopK() > 0) {
      query.setRows(request.getTopK());
    }
    convertFilterToSolrQuery(request.getFilterExpression())
        .filter(solrFilter -> !solrFilter.isEmpty())
        .ifPresent(query::addFilterQuery);
    query.setFields(
        options.idFieldName(),
        options.contentFieldName(),
        SCORE_FIELD,
        options.metadataPrefix() + WILDCARD);
    query.setParam(QUERY_TYPE_PARAM, SELECT_HANDLER);
    return query;
  }

  /** A negative threshold disables filtering; otherwise unscored documents are dropped. */
  private static boolean meetsThreshold(Document document, double threshold) {
    if (threshold < 0) {
      return true;
    }
    return document.getMetadata().get(SCORE_FIELD) instanceof Number score
        && score.doubleValue() >= threshold;
  }

  /** Creates observation context builder for metrics. */
  @Override
  public VectorStoreObservationContext.Builder createObservationContextBuilder(
      String operationType) {
    return VectorStoreObservationContext.builder("solr", operationType)
        .collectionName(this.collection)
        .dimensions(this.options.vectorDimension())
        .namespace(this.collection);
  }

  /**
   * Returns the native Solr client.
   *
   * @param <T> the type of the native client
   * @return an Optional containing the SolrClient instance
   */
  @Override
  @SuppressWarnings("unchecked")
  public <T> Optional<T> getNativeClient() {
    return (Optional<T>) Optional.of(this.solrClient);
  }

  private void initializeSchema() {
    // Schema initialization could be added here if needed
    // For now, we assume the schema is already configured in Solr
    log.debug("Schema initialization not implemented - assuming schema exists in Solr");
  }

  /** Converts Spring AI filter expressions to Solr query syntax. */
  private Optional<String> convertFilterToSolrQuery(Filter.@Nullable Expression filterExpression) {
    return Optional.ofNullable(filterExpression).map(this::convertOperand);
  }

  private String convertOperand(Filter.@Nullable Operand operand) {
    if (operand == null) {
      // Unary operators (for example NOT) carry no right-hand operand.
      return "";
    }
    return switch (operand) {
      case Filter.Expression expression -> convertExpression(expression);
      case Filter.Group group -> "(" + convertOperand(group.content()) + ")";
      case Filter.Key key -> withMetadataPrefix(key.key());
      case Filter.Value value ->
          value.value() instanceof String text ? "\"" + text + "\"" : String.valueOf(value.value());
      default -> "";
    };
  }

  private String withMetadataPrefix(String fieldName) {
    return fieldName.startsWith(options.metadataPrefix())
        ? fieldName
        : options.metadataPrefix() + fieldName;
  }

  private String convertExpression(Filter.Expression expression) {
    String left = convertOperand(expression.left());
    String right = convertOperand(expression.right());

    return switch (expression.type()) {
      case EQ -> left + ":" + right;
      case NE -> "-" + left + ":" + right;
      case GT -> left + ":{" + right + " TO *]";
      case GTE -> left + ":[" + right + " TO *]";
      case LT -> left + ":[* TO " + right + "}";
      case LTE -> left + ":[* TO " + right + "]";
      case AND -> left + " AND " + right;
      case OR -> left + " OR " + right;
      case IN -> left + ":(" + right.replace("\"", "") + ")"; // Simplification for IN
      default -> {
        log.warn("Unsupported filter expression type, ignoring: {}", expression.type());
        yield "";
      }
    };
  }

  private SolrInputDocument toSolrDocument(Document document) {
    SolrInputDocument solrDoc = new SolrInputDocument();

    String id = document.getId() != null ? document.getId() : UUID.randomUUID().toString();
    solrDoc.addField(options.idFieldName(), id);
    solrDoc.addField(options.contentFieldName(), document.getText());
    embeddingOf(document)
        .ifPresent(
            embedding -> solrDoc.addField(options.vectorFieldName(), toFloatList(embedding)));

    document
        .getMetadata()
        .forEach(
            (key, value) -> {
              if (isUserMetadata(key)) {
                solrDoc.addField(options.metadataPrefix() + key, value);
              }
            });
    return solrDoc;
  }

  private static List<Float> toFloatList(float[] values) {
    List<Float> list = new ArrayList<>(values.length);
    for (float value : values) {
      list.add(value);
    }
    return list;
  }

  /** The id, content and embedding are stored in their own fields, not as metadata. */
  private boolean isUserMetadata(String key) {
    return !options.idFieldName().equals(key)
        && !options.contentFieldName().equals(key)
        && !options.vectorFieldName().equals(key)
        && !EMBEDDING_METADATA_KEY.equals(key);
  }

  /** A Solr document without an id cannot be represented as a Spring AI Document. */
  private Optional<Document> toDocument(SolrDocument solrDoc) {
    return firstValueAsString(solrDoc.getFieldValue(options.idFieldName()))
        .map(
            id ->
                Document.builder()
                    .id(id)
                    .text(
                        firstValueAsString(solrDoc.getFieldValue(options.contentFieldName()))
                            .orElse(null))
                    .metadata(extractMetadata(solrDoc))
                    .build());
  }

  private Map<String, Object> extractMetadata(SolrDocument solrDoc) {
    Map<String, Object> metadata = new HashMap<>();
    for (String fieldName : solrDoc.getFieldNames()) {
      if (!fieldName.startsWith(options.metadataPrefix())) {
        continue;
      }
      String metadataKey = fieldName.substring(options.metadataPrefix().length());
      firstValue(solrDoc.getFieldValue(fieldName))
          .ifPresent(
              value -> metadata.put(metadataKey, normalizeMetadataValue(metadataKey, value)));
    }
    if (solrDoc.getFieldValue(SCORE_FIELD) instanceof Number score) {
      metadata.put(SCORE_FIELD, score.doubleValue());
    }
    return metadata;
  }

  /** Solr returns integer fields as Long; callers expect the year as an Integer. */
  private static Object normalizeMetadataValue(String metadataKey, Object value) {
    return YEAR_METADATA_KEY.equals(metadataKey) && value instanceof Long year
        ? year.intValue()
        : value;
  }

  /** Solr may return any field as multi-valued; only the first value is kept. */
  private static Optional<Object> firstValue(@Nullable Object fieldValue) {
    if (fieldValue instanceof List<?> values) {
      return values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.get(0));
    }
    return Optional.ofNullable(fieldValue);
  }

  private static Optional<String> firstValueAsString(@Nullable Object fieldValue) {
    return firstValue(fieldValue).map(Object::toString);
  }

  /** Builder for SolrVectorStore. */
  public static final class Builder extends AbstractVectorStoreBuilder<Builder> {
    private final SolrClient solrClient;
    private final String collection;
    private @Nullable SolrVectorStoreOptions options;
    private boolean initializeSchema = false;

    private Builder(SolrClient solrClient, String collection, EmbeddingModel embeddingModel) {
      super(embeddingModel);
      this.solrClient = solrClient;
      this.collection = collection;
    }

    public Builder options(SolrVectorStoreOptions options) {
      this.options = options;
      return this;
    }

    public Builder initializeSchema(boolean initializeSchema) {
      this.initializeSchema = initializeSchema;
      return this;
    }

    @Override
    public SolrVectorStore build() {
      return new SolrVectorStore(this);
    }
  }
}
