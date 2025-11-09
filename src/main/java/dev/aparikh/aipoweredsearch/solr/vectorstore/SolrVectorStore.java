package dev.aparikh.aipoweredsearch.solr.vectorstore;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static java.util.stream.Collectors.toList;

/**
 * Apache Solr vector store implementation for Spring AI document storage and retrieval.
 *
 * <p>This class provides a full-featured vector store using Apache Solr's dense vector search
 * capabilities (Solr 9.0+). It enables semantic search by storing document embeddings
 * and performing similarity-based retrieval using KNN (K-Nearest Neighbors) algorithms.
 *
 * <p>Key features:
 * <ul>
 *   <li>Automatic embedding generation using OpenAI's text-embedding-3-small model</li>
 *   <li>HNSW (Hierarchical Navigable Small World) algorithm for efficient similarity search</li>
 *   <li>Cosine similarity metric for semantic matching</li>
 *   <li>Metadata filtering with Spring AI filter expressions</li>
 *   <li>Observability support via Micrometer metrics</li>
 *   <li>Batch document processing for improved performance</li>
 * </ul>
 *
 * <h3>Solr Schema Requirements:</h3>
 * <p>The Solr collection must have the following fields defined:</p>
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

    private static final Logger log = LoggerFactory.getLogger(SolrVectorStore.class);

    private final SolrClient solrClient;
    private final String collection;
    private final SolrVectorStoreOptions options;
    private final boolean initializeSchema;

    /**
     * Protected constructor - use Builder to create instances.
     */
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
    public static Builder builder(SolrClient solrClient, String collection, EmbeddingModel embeddingModel) {
        return new Builder(solrClient, collection, embeddingModel);
    }

    /**
     * Adds documents with embeddings to the vector store.
     * Embeddings are generated automatically if not present in metadata.
     *
     * @param documents list of documents to add
     */
    @Override
    public void doAdd(List<Document> documents) {
        try {
            // Separate documents into those with and without embeddings
            List<Document> documentsWithoutEmbeddings = documents.stream()
                    .filter(doc -> {
                        Object embedding = doc.getMetadata().get("embedding");
                        return embedding == null || !(embedding instanceof float[]) || ((float[]) embedding).length == 0;
                    })
                    .toList();

            // Generate embeddings in batch for efficiency
            if (!documentsWithoutEmbeddings.isEmpty()) {
                List<String> texts = documentsWithoutEmbeddings.stream()
                        .map(Document::getText)
                        .toList();

                EmbeddingResponse embeddingResponse = this.embeddingModel.embedForResponse(texts);

                if (embeddingResponse.getResults().size() != documentsWithoutEmbeddings.size()) {
                    throw new RuntimeException("Expected " + documentsWithoutEmbeddings.size() +
                            " embeddings but got " + embeddingResponse.getResults().size());
                }

                // Add embeddings to documents
                for (int i = 0; i < documentsWithoutEmbeddings.size(); i++) {
                    Document doc = documentsWithoutEmbeddings.get(i);
                    float[] embedding = embeddingResponse.getResults().get(i).getOutput();
                    doc.getMetadata().put("embedding", embedding);
                }
            }

            // Convert to Solr documents
            List<SolrInputDocument> solrDocs = documents.stream()
                    .map(this::toSolrDocument)
                    .collect(toList());

            // Add to Solr
            UpdateResponse response = solrClient.add(collection, solrDocs);
            solrClient.commit(collection);

            log.debug("Added {} documents to Solr collection '{}', status: {}",
                    documents.size(), collection, response.getStatus());
        } catch (SolrServerException | IOException e) {
            throw new RuntimeException("Failed to add documents to Solr", e);
        }
    }

    /**
     * Deletes documents by ID.
     *
     * @param idList list of document IDs to delete
     */
    @Override
    public void doDelete(List<String> idList) {
        try {
            UpdateResponse response = solrClient.deleteById(collection, idList);
            solrClient.commit(collection);

            log.debug("Deleted {} documents from Solr collection '{}', status: {}",
                    idList.size(), collection, response.getStatus());
        } catch (SolrServerException | IOException e) {
            throw new RuntimeException("Failed to delete documents from Solr", e);
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
        try {
            // Validate query
            if (request.getQuery() == null || request.getQuery().isEmpty()) {
                throw new IllegalArgumentException("Search query cannot be null or empty");
            }

            // Generate embedding for query text
            EmbeddingResponse embeddingResponse = this.embeddingModel.embedForResponse(
                    Collections.singletonList(request.getQuery()));

            if (embeddingResponse.getResults().isEmpty()) {
                throw new RuntimeException("Failed to generate embedding for query");
            }

            float[] queryEmbedding = embeddingResponse.getResults().getFirst().getOutput();

            // Build KNN query
            String vectorString = floatArrayToString(queryEmbedding);
            String knnQuery = String.format("{!knn f=%s topK=%d}%s",
                    options.vectorFieldName(), request.getTopK(), vectorString);

            SolrQuery query = new SolrQuery(knnQuery);
            // Ensure Solr returns up to topK results
            if (request.getTopK() > 0) {
                query.setRows(request.getTopK());
            }

            // Apply filter expression if present
            if (request.getFilterExpression() != null) {
                String solrFilter = convertFilterToSolrQuery(request.getFilterExpression());
                if (solrFilter != null && !solrFilter.isEmpty()) {
                    query.addFilterQuery(solrFilter);
                }
            }

            // Set fields to return (include score pseudo-field for similarity scoring)
            query.setFields(options.idFieldName(), options.contentFieldName(),
                    "score", options.metadataPrefix() + "*");

            // Note: Similarity threshold filtering must be done post-query
            // because "score" is a pseudo-field that doesn't exist until query time
            // We'll filter results after retrieval based on the threshold

            // Use POST method to avoid URI Too Long errors with large vector embeddings
            query.setRequestHandler("/select");
            QueryResponse response = solrClient.query(collection, query, SolrRequest.METHOD.POST);
            List<SolrDocument> results = response.getResults();

            log.debug("Similarity search in collection '{}' returned {} results", collection, results.size());

            // Filter results by similarity threshold if specified
            double threshold = request.getSimilarityThreshold();
            return results.stream()
                    .map(solrDoc -> toDocument(solrDoc, threshold))
                    .filter(Objects::nonNull)
                    .filter(doc -> {
                        // Filter by threshold if specified
                        if (threshold >= 0) {
                            Object scoreObj = doc.getMetadata().get("score");
                            if (scoreObj instanceof Number) {
                                double score = ((Number) scoreObj).doubleValue();
                                return score >= threshold;
                            }
                            return false;
                        }
                        return true;
                    })
                    .toList();
        } catch (SolrServerException | IOException e) {
            throw new RuntimeException("Failed to perform similarity search in Solr", e);
        }
    }

    /**
     * Creates observation context builder for metrics.
     */
    @Override
    public VectorStoreObservationContext.Builder createObservationContextBuilder(String operationType) {
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

    /**
     * Converts Spring AI filter expressions to Solr query syntax.
     * Spring AI uses expressions like: category == 'AI' or year == 2024
     * Solr expects: metadata_category:AI or metadata_year:2024
     */
    private String convertFilterToSolrQuery(Filter.Expression filterExpression) {
        if (filterExpression == null) {
            return null;
        }

        // Check if this is an equality expression
        if (filterExpression instanceof Filter.Expression) {
            // Get the type of expression
            String exprType = getExpressionType(filterExpression);

            if ("EQ".equals(exprType)) {
                // Extract key and value from the expression
                String key = getExpressionKey(filterExpression);
                String value = getExpressionValue(filterExpression);

                if (key != null && value != null) {
                    // Add metadata prefix if not already present
                    if (!key.startsWith(options.metadataPrefix())) {
                        key = options.metadataPrefix() + key;
                    }

                    // Remove quotes if present
                    value = value.replaceAll("^['\"]|['\"]$", "");

                    // Build Solr filter query
                    String solrQuery = key + ":" + value;
                    log.debug("Converted filter expression to Solr query: {}", solrQuery);
                    return solrQuery;
                }
            }
        }

        // Fallback: try to parse the string representation
        String filterStr = filterExpression.toString();
        log.debug("Attempting to parse filter expression string: {}", filterStr);

        // Try to extract from string like "Expression[type=EQ, left=Key[key=category], right=Value[value=AI]]"
        if (filterStr.contains("type=EQ") && filterStr.contains("Key[key=") && filterStr.contains("Value[value=")) {
            String key = extractBetween(filterStr, "Key[key=", "]");
            String value = extractBetween(filterStr, "Value[value=", "]");

            if (key != null && value != null) {
                if (!key.startsWith(options.metadataPrefix())) {
                    key = options.metadataPrefix() + key;
                }
                String solrQuery = key + ":" + value;
                log.debug("Extracted and converted to Solr query: {}", solrQuery);
                return solrQuery;
            }
        }

        log.warn("Could not convert filter expression to Solr syntax: {}", filterStr);
        return null;
    }

    private String getExpressionType(Filter.Expression expr) {
        try {
            // Use reflection to get the type
            var typeField = expr.getClass().getDeclaredField("type");
            typeField.setAccessible(true);
            Object type = typeField.get(expr);
            return type != null ? type.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String getExpressionKey(Filter.Expression expr) {
        try {
            // Use reflection to get the left/key
            var leftField = expr.getClass().getDeclaredField("left");
            leftField.setAccessible(true);
            Object left = leftField.get(expr);
            if (left != null) {
                var keyField = left.getClass().getDeclaredField("key");
                keyField.setAccessible(true);
                return (String) keyField.get(left);
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private String getExpressionValue(Filter.Expression expr) {
        try {
            // Use reflection to get the right/value
            var rightField = expr.getClass().getDeclaredField("right");
            rightField.setAccessible(true);
            Object right = rightField.get(expr);
            if (right != null) {
                var valueField = right.getClass().getDeclaredField("value");
                valueField.setAccessible(true);
                Object value = valueField.get(right);
                return value != null ? value.toString() : null;
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private String extractBetween(String str, String start, String end) {
        int startIdx = str.indexOf(start);
        if (startIdx == -1) return null;
        startIdx += start.length();

        int endIdx = str.indexOf(end, startIdx);
        if (endIdx == -1) return null;

        return str.substring(startIdx, endIdx);
    }

    private String floatArrayToString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private SolrInputDocument toSolrDocument(Document document) {
        SolrInputDocument solrDoc = new SolrInputDocument();

        // Set ID
        String id = document.getId() != null ? document.getId() : UUID.randomUUID().toString();
        solrDoc.addField(options.idFieldName(), id);

        // Set content
        solrDoc.addField(options.contentFieldName(), document.getText());

        // Set vector embedding from metadata
        Object embeddingObj = document.getMetadata().get("embedding");

        if (embeddingObj instanceof float[]) {
            float[] embedding = (float[]) embeddingObj;
            List<Float> embeddingList = new ArrayList<>();
            for (float val : embedding) {
                embeddingList.add(val);
            }
            solrDoc.addField(options.vectorFieldName(), embeddingList);
        }

        // Add metadata fields with prefix (skip embedding as it's stored separately)
        if (document.getMetadata() != null) {
            document.getMetadata().forEach((key, value) -> {
                if (!options.idFieldName().equals(key) &&
                    !options.contentFieldName().equals(key) &&
                    !options.vectorFieldName().equals(key) &&
                    !"embedding".equals(key)) {
                    solrDoc.addField(options.metadataPrefix() + key, value);
                }
            });
        }

        return solrDoc;
    }

    private Document toDocument(SolrDocument solrDoc, double similarityThreshold) {
        // Handle potential multi-valued fields from Solr
        Object idObj = solrDoc.getFieldValue(options.idFieldName());
        String id = null;
        if (idObj instanceof List) {
            List<?> idList = (List<?>) idObj;
            if (!idList.isEmpty()) {
                id = idList.get(0).toString();
            }
        } else if (idObj != null) {
            id = idObj.toString();
        }

        Object contentObj = solrDoc.getFieldValue(options.contentFieldName());
        String content = null;
        if (contentObj instanceof List) {
            List<?> contentList = (List<?>) contentObj;
            if (!contentList.isEmpty()) {
                content = contentList.get(0).toString();
            }
        } else if (contentObj != null) {
            content = contentObj.toString();
        }

        // Extract score and normalize for cosine similarity
        Number scoreNum = (Number) solrDoc.getFieldValue("score");
        Double score = scoreNum != null ? scoreNum.doubleValue() : null;

        // Apply similarity threshold if score is available
        if (score != null && similarityThreshold >= 0) {
            // Solr returns cosine similarity in [0,1] range with 1 being most similar
            if (score < similarityThreshold) {
                return null; // Filter out documents below threshold
            }
        }

        // Extract vector embedding
        float[] embedding = null;
        Object vectorObj = solrDoc.getFieldValue(options.vectorFieldName());
        if (vectorObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Number> vectorList = (List<Number>) vectorObj;
            embedding = new float[vectorList.size()];
            for (int i = 0; i < vectorList.size(); i++) {
                embedding[i] = vectorList.get(i).floatValue();
            }
        }

        // Extract metadata (fields with metadata prefix)
        Map<String, Object> metadata = new HashMap<>();
        solrDoc.getFieldNames().forEach(fieldName -> {
            if (fieldName.startsWith(options.metadataPrefix())) {
                String metadataKey = fieldName.substring(options.metadataPrefix().length());
                Object metadataValue = solrDoc.getFieldValue(fieldName);

                // Handle multi-valued fields - extract first value if it's a list
                if (metadataValue instanceof List) {
                    List<?> valueList = (List<?>) metadataValue;
                    if (!valueList.isEmpty()) {
                        Object firstValue = valueList.get(0);
                        // Convert Long to Integer for year field
                        if ("year".equals(metadataKey) && firstValue instanceof Long) {
                            metadata.put(metadataKey, ((Long) firstValue).intValue());
                        } else {
                            metadata.put(metadataKey, firstValue);
                        }
                    }
                } else if (metadataValue != null) {
                    // Convert Long to Integer for year field
                    if ("year".equals(metadataKey) && metadataValue instanceof Long) {
                        metadata.put(metadataKey, ((Long) metadataValue).intValue());
                    } else {
                        metadata.put(metadataKey, metadataValue);
                    }
                }
            }
        });

        // Add score if present
        if (score != null) {
            metadata.put("score", score);
        }


        // Build document
        return Document.builder()
                .id(id)
                .text(content)
                .metadata(metadata)
                .build();
    }

    /**
     * Builder for SolrVectorStore.
     */
    public static final class Builder extends AbstractVectorStoreBuilder<Builder> {
        private final SolrClient solrClient;
        private final String collection;
        private SolrVectorStoreOptions options;
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
