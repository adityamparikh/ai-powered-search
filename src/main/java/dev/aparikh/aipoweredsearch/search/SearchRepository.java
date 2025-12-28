package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.embedding.EmbeddingService;
import dev.aparikh.aipoweredsearch.search.model.FieldInfo;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import dev.aparikh.aipoweredsearch.solr.SolrQueryUtils;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.schema.SchemaResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@Repository
public class SearchRepository {

    // Essential field name constants (required for vector search)
    private static final String FIELD_VECTOR = "vector";
    private static final String FIELD_SCORE = "score";
    private static final String FIELD_TEXT_CATCHALL = "_text_";

    // Query type constants
    private static final String WILDCARD_QUERY = "*:*";
    private static final String QUERY_TYPE_EDISMAX = "edismax";

    Logger log = LoggerFactory.getLogger(SearchRepository.class);
    private final SolrClient solrClient;
    private final EmbeddingService embeddingService;

    public SearchRepository(SolrClient solrClient, EmbeddingService embeddingService) {
        this.solrClient = solrClient;
        this.embeddingService = embeddingService;
    }

    public SearchResponse search(String collection, SearchRequest searchRequest) {
        SolrQuery query = new SolrQuery(searchRequest.query());

        // Use edismax for text queries (better than standard query parser)
        // Only apply for non-wildcard queries
        if (!WILDCARD_QUERY.equals(searchRequest.query())) {
            query.set("defType", QUERY_TYPE_EDISMAX);
            // Use catch-all field that Solr populates from all text fields
            query.set("qf", FIELD_TEXT_CATCHALL);
        }

        if (searchRequest.filterQueries() != null) {
            searchRequest.filterQueries().forEach(query::addFilterQuery);
        }

        if (searchRequest.hasSort()) {
            query.set("sort", searchRequest.sort());
        }

        if (searchRequest.hasFieldList()) {
            query.setFields(searchRequest.fieldList());
        }

        if (searchRequest.hasFacets()) {
            query.setFacet(true);
            searchRequest.facet().fields().forEach(query::addFacetField);
            if (searchRequest.facet().query() != null) {
                query.addFacetQuery(searchRequest.facet().query());
            }
        }

        // Enable spell checking (works without knowing specific fields)
        query.setParam("spellcheck", "true");
        query.setParam("spellcheck.q", searchRequest.query());
        query.setParam("spellcheck.collate", "true");

        try {
            QueryResponse response = solrClient.query(collection, query);

            // Handle facet fields - they can be null if no facets are requested
            Map<String, List<SearchResponse.FacetCount>> facetCountsMap =
                response.getFacetFields() != null ?
                    response.getFacetFields().stream()
                            .collect(Collectors.toMap(
                                    f -> f.getName(),
                                    f -> f.getValues().stream()
                                            .map(c -> new SearchResponse.FacetCount(c.getName(), c.getCount()))
                                            .collect(Collectors.toList())))
                        : new HashMap<>();

            return new SearchResponse(
                    response.getResults().stream()
                            .map(HashMap::new)
                            .collect(Collectors.toList()),
                    facetCountsMap,
                    Map.of(), // No highlighting without knowing field names
                    extractSpellCheck(response, searchRequest.query())
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    public Set<String> getActuallyUsedFields(String collection) {
        Set<String> usedFields = new HashSet<>();

        try {
            // Query sample of documents
            SolrQuery query = new SolrQuery("*:*");
            query.setRows(100);

            QueryResponse response = solrClient.query(collection, query);

            for (SolrDocument doc : response.getResults()) {
                usedFields.addAll(doc.getFieldNames());
            }
        } catch (Exception e) {
            log.error("Error analyzing fields", e);
        }
        return usedFields;
    }

    /**
     * Executes hybrid search combining keyword and vector search using native RRF (Reciprocal Rank Fusion).
     *
     * <p>This method implements hybrid search with intelligent fallback:
     * 1. Attempts hybrid search using native RRF (keyword + vector fusion)
     * 2. If no results, falls back to keyword-only search
     * 3. If keyword fails, falls back to vector-only search
     * 4. Combines both signals using Solr's native RRF query parser (available in Solr 9.8+)</p>
     *
     * <p>RRF (Reciprocal Rank Fusion) provides better fusion than reranking by:
     * <ul>
     *   <li>Using the formula: score = sum(1 / (k + rank)) for each result list</li>
     *   <li>Balancing keyword and vector signals more evenly</li>
     *   <li>Handling rank positions rather than raw scores</li>
     *   <li>Configurable k parameter for fusion tuning (default: 60)</li>
     * </ul></p>
     *
     * @param collection        the Solr collection to search
     * @param query             the text query for keyword search
     * @param topK              number of results to return
     * @param filterExpression  optional filter query
     * @param fieldsCsv         optional comma-separated list of fields to return
     * @param minScore          optional minimum score threshold
     * @return search response with hybrid-ranked documents
     */
    public SearchResponse executeHybridRerankSearch(String collection,
                                                    String query,
                                                    int topK,
                                                    @Nullable String filterExpression,
                                                    @Nullable String fieldsCsv,
                                                    @Nullable Double minScore) {
        log.debug("Performing hybrid search (RRF) in collection: {} with query: {}", collection, query);

        try {
            // Step 1: Generate embedding for the query text
            String vectorString = embeddingService.embedAndFormatForSolr(query);

            // Step 2: Build hybrid query using native RRF
            ModifiableSolrParams params = new ModifiableSolrParams();

            // Native RRF query combines keyword and vector search
            // Format: {!rrf}({!edismax qf='_text_'}query)({!knn f=vector topK=N}[vector])
            // Use catch-all field (_text_) which Solr automatically populates from all text fields
            String keywordQuery = String.format("{!edismax qf='%s'}%s", FIELD_TEXT_CATCHALL, query);
            String vectorQuery = SolrQueryUtils.buildKnnQuery(FIELD_VECTOR, topK * 2, vectorString); // Fetch more for better fusion
            params.set("q", String.format("{!rrf}(%s)(%s)", keywordQuery, vectorQuery));

            configureQueryParams(params, filterExpression, fieldsCsv, topK);
            configureSpellCheck(params, query);

            // Step 3: Execute query using POST to avoid URI Too Long errors with large vector embeddings
            QueryResponse response = solrClient.query(collection, params, SolrRequest.METHOD.POST);

            log.debug("Hybrid search with RRF returned {} results", response.getResults().size());

            // Step 4: Convert results (apply minScore filter and handle multi-valued fields)
            List<Map<String, Object>> documents = convertDocuments(response.getResults(), minScore);

            // Step 5: Implement fallback if no results
            if (documents.isEmpty()) {
                log.warn("Hybrid search returned no results, attempting keyword-only fallback");
                return fallbackToKeywordSearch(collection, query, topK, filterExpression, fieldsCsv, minScore);
            }

            return new SearchResponse(documents, Map.of(), Map.of(), extractSpellCheck(response, query));

        } catch (Exception e) {
            log.error("Error performing hybrid search with reranking in collection: {}", collection, e);
            log.warn("Hybrid search failed, attempting keyword-only fallback");
            return fallbackToKeywordSearch(collection, query, topK, filterExpression, fieldsCsv, minScore);
        }
    }

    /**
     * Fallback to keyword-only search when hybrid search fails or returns no results.
     * If keyword search also fails, attempts vector-only search as last resort.
     */
    private SearchResponse fallbackToKeywordSearch(String collection,
                                                   String query,
                                                   int topK,
                                                   @Nullable String filterExpression,
                                                   @Nullable String fieldsCsv,
                                                   @Nullable Double minScore) {
        try {
            log.debug("Attempting keyword-only search fallback");
            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set("q", query);
            params.set("defType", QUERY_TYPE_EDISMAX);
            params.set("qf", FIELD_TEXT_CATCHALL);

            configureQueryParams(params, filterExpression, fieldsCsv, topK);

            QueryResponse response = solrClient.query(collection, params, SolrRequest.METHOD.POST);
            List<Map<String, Object>> documents = convertDocuments(response.getResults(), minScore);

            if (documents.isEmpty()) {
                log.warn("Keyword search fallback returned no results, attempting vector-only search");
                return fallbackToVectorSearch(collection, query, topK, filterExpression, fieldsCsv, minScore);
            }

            log.info("Keyword-only fallback succeeded with {} results", documents.size());
            return new SearchResponse(documents, new HashMap<>());

        } catch (Exception e) {
            log.error("Keyword search fallback failed", e);
            log.warn("Attempting vector-only search as last resort");
            return fallbackToVectorSearch(collection, query, topK, filterExpression, fieldsCsv, minScore);
        }
    }

    /**
     * Last resort fallback to vector-only search.
     */
    private SearchResponse fallbackToVectorSearch(String collection,
                                                  String query,
                                                  int topK,
                                                  @Nullable String filterExpression,
                                                  @Nullable String fieldsCsv,
                                                  @Nullable Double minScore) {
        try {
            log.debug("Attempting vector-only search fallback");
            String vectorString = embeddingService.embedAndFormatForSolr(query);

            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set("q", SolrQueryUtils.buildKnnQuery(topK, vectorString));

            configureQueryParams(params, filterExpression, fieldsCsv, topK);

            QueryResponse response = solrClient.query(collection, params, SolrRequest.METHOD.POST);
            List<Map<String, Object>> documents = convertDocuments(response.getResults(), minScore);

            log.info("Vector-only fallback returned {} results", documents.size());
            return new SearchResponse(documents, new HashMap<>());

        } catch (Exception e) {
            log.error("All search strategies failed (hybrid, keyword, vector)", e);
            // Return empty results rather than throwing exception
            return new SearchResponse(new ArrayList<>(), new HashMap<>());
        }
    }

    /**
     * Fetches field schema information from Solr including field types and attributes
     */
    public List<FieldInfo> getFieldsWithSchema(String collection) {
        List<FieldInfo> fieldInfos = new ArrayList<>();

        try {
            // 1) Fetch explicit fields
            SchemaResponse.FieldsResponse fieldsResponse = new SchemaRequest.Fields()
                    .process(solrClient, collection);
            List<Map<String, Object>> explicitFields = fieldsResponse.getFields();

            // Build a quick lookup for explicit fields by name
            Map<String, Map<String, Object>> explicitByName = explicitFields.stream()
                    .collect(Collectors.toMap(f -> (String) f.get("name"), f -> f));

            // 2) Fetch dynamic field definitions (e.g., metadata_*, *_i, *_txt, etc.)
            SchemaResponse.DynamicFieldsResponse dynamicFieldsResponse = new SchemaRequest.DynamicFields()
                    .process(solrClient, collection);
            List<Map<String, Object>> dynamicFields = dynamicFieldsResponse.getDynamicFields();

            // 3) Determine actually used field names from documents
            Set<String> usedFields = getActuallyUsedFields(collection);

            // 4) For each used field, resolve attributes from explicit schema or best matching dynamic field
            for (String fieldName : usedFields) {
                if (fieldName == null || fieldName.isEmpty() || fieldName.startsWith("_")) {
                    // Skip internal or invalid fields
                    continue;
                }

                Map<String, Object> schemaField = explicitByName.get(fieldName);
                if (schemaField != null) {
                    fieldInfos.add(extractFieldInfo(fieldName, schemaField));
                    continue;
                }

                // Try to match a dynamic field pattern
                Map<String, Object> bestMatch = findBestDynamicFieldMatch(fieldName, dynamicFields);
                if (bestMatch != null) {
                    fieldInfos.add(extractFieldInfo(fieldName, bestMatch));
                }
                // If no dynamic match, we skip this field to avoid returning unknowns when schema is available
            }

            log.debug("Resolved {} used fields with schema (including dynamic fields) from collection {}", fieldInfos.size(), collection);

        } catch (Exception e) {
            log.error("Error fetching field schema from Solr", e);
            // Fallback: return fields without type information
            Set<String> usedFields = getActuallyUsedFields(collection);
            for (String fieldName : usedFields) {
                if (!fieldName.startsWith("_")) {
                    fieldInfos.add(new FieldInfo(fieldName, "unknown", false, true, false, true));
                }
            }
        }

        return fieldInfos;
    }

    /**
     * Finds the best matching dynamic field definition for a given field name.
     */
    @Nullable
    private Map<String, Object> findBestDynamicFieldMatch(String fieldName, List<Map<String, Object>> dynamicFields) {
        Map<String, Object> bestMatch = null;
        int bestScore = -1; // higher is better
        for (Map<String, Object> df : dynamicFields) {
            String pattern = (String) df.get("name");
            if (pattern == null) continue;

            int score = dynamicMatchScore(fieldName, pattern);
            if (score >= 0 && score > bestScore) {
                bestScore = score;
                bestMatch = df;
            }
        }
        return bestMatch;
    }

    // Returns a non-negative score if fieldName matches the dynamic pattern; -1 otherwise.
    // Score represents the length of the fixed part of the pattern (longer = more specific match).
    // Solr dynamic fields support a single wildcard either at the start or the end.
    private int dynamicMatchScore(String fieldName, String pattern) {
        if (pattern == null || fieldName == null) return -1;
        if (pattern.equals(fieldName)) return pattern.length();

        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return fieldName.startsWith(prefix) ? prefix.length() : -1;
        }

        if (pattern.startsWith("*")) {
            String suffix = pattern.substring(1);
            return fieldName.endsWith(suffix) ? suffix.length() : -1;
        }

        // No wildcard and not equal => does not match
        return -1;
    }

    /**
     * Extracts FieldInfo from a schema field map.
     */
    private FieldInfo extractFieldInfo(String fieldName, Map<String, Object> schemaField) {
        String type = (String) schemaField.get("type");
        boolean multiValued = Boolean.TRUE.equals(schemaField.get("multiValued"));
        boolean stored = !Boolean.FALSE.equals(schemaField.get("stored")); // default true
        boolean docValues = Boolean.TRUE.equals(schemaField.get("docValues"));
        boolean indexed = !Boolean.FALSE.equals(schemaField.get("indexed")); // default true
        return new FieldInfo(fieldName, type, multiValued, stored, docValues, indexed);
    }

    /**
     * Configures common query parameters for filter, fields, and rows.
     */
    private void configureQueryParams(ModifiableSolrParams params,
                                      @Nullable String filterExpression,
                                      @Nullable String fieldsCsv,
                                      int topK) {
        if (filterExpression != null && !filterExpression.isEmpty()) {
            params.set("fq", filterExpression);
        }
        if (fieldsCsv != null && !fieldsCsv.isEmpty()) {
            params.set("fl", fieldsCsv + "," + FIELD_SCORE);
        } else {
            params.set("fl", "*," + FIELD_SCORE);
        }
        params.set("rows", topK);
    }

    /**
     * Configures spell check parameters.
     */
    private void configureSpellCheck(ModifiableSolrParams params, String query) {
        params.set("spellcheck", "true");
        params.set("spellcheck.q", query);
        params.set("spellcheck.collate", "true");
    }

    /**
     * Converts SolrDocumentList to a list of Maps, applying minScore filter and flattening multi-valued fields.
     */
    private List<Map<String, Object>> convertDocuments(SolrDocumentList results, @Nullable Double minScore) {
        return results.stream()
                .filter(doc -> passesMinScoreFilter(doc, minScore))
                .map(this::flattenDocument)
                .collect(Collectors.toList());
    }

    /**
     * Checks if a document passes the minimum score filter.
     */
    private boolean passesMinScoreFilter(SolrDocument doc, @Nullable Double minScore) {
        if (minScore == null) {
            return true;
        }
        Object scoreObj = doc.getFieldValue(FIELD_SCORE);
        if (scoreObj instanceof Number number) {
            return number.doubleValue() >= minScore;
        }
        return true;
    }

    /**
     * Flattens a SolrDocument to a Map, extracting first values from multi-valued fields.
     */
    private Map<String, Object> flattenDocument(SolrDocument doc) {
        Map<String, Object> docMap = new HashMap<>();
        for (String fieldName : doc.getFieldNames()) {
            Object value = doc.getFieldValue(fieldName);
            if (value instanceof List<?> list) {
                if (!list.isEmpty()) {
                    docMap.put(fieldName, list.getFirst());
                }
            } else {
                docMap.put(fieldName, value);
            }
        }
        return docMap;
    }

    /**
     * Extracts spell check suggestion from query response.
     */
    private SearchResponse.@Nullable SpellCheckSuggestion extractSpellCheck(QueryResponse response, String originalQuery) {
        if (response.getSpellCheckResponse() != null &&
                response.getSpellCheckResponse().getCollatedResult() != null) {
            String collation = response.getSpellCheckResponse().getCollatedResult();
            if (!collation.equals(originalQuery)) {
                return new SearchResponse.SpellCheckSuggestion(collation, originalQuery);
            }
        }
        return null;
    }

    /**
     * Performs semantic search using vector similarity with Solr-native filter support.
     *
     * <p>This method bypasses Spring AI's filter expression parser and applies filters directly
     * to Solr, allowing use of Solr-native filter syntax like "year:[2020 TO *]" that is
     * generated by Claude AI.
     *
     * @param collection  the Solr collection to search
     * @param query       the search query text (will be embedded)
     * @param topK        number of top results to return
     * @param filterQuery optional Solr-native filter query (e.g., "year:[2020 TO *]", "category:tech")
     * @param fieldsCsv   optional comma-separated list of fields to return
     * @param minScore    optional minimum similarity score threshold
     * @return search response with semantically similar documents
     */
    public SearchResponse semanticSearch(String collection, String query, int topK, String filterQuery, String fieldsCsv, Double minScore) {
        try {
            // Generate embedding for the query
            String vectorString = embeddingService.embedAndFormatForSolr(query);

            // Build KNN query for vector search
            SolrQuery solrQuery = new SolrQuery();
            solrQuery.setQuery(SolrQueryUtils.buildKnnQuery(FIELD_VECTOR, topK, vectorString));
            solrQuery.setRows(topK);

            // Apply Solr-native filter if provided
            if (filterQuery != null && !filterQuery.isBlank()) {
                log.debug("Applying Solr-native filter: {}", filterQuery);
                solrQuery.addFilterQuery(filterQuery);
            }

            // Set fields to return
            if (fieldsCsv != null && !fieldsCsv.isBlank()) {
                solrQuery.setFields(fieldsCsv.split(","));
            } else {
                solrQuery.setFields("*", FIELD_SCORE);
            }

            // No faceting for semantic search (would require knowing field names)

            // Execute search
            QueryResponse response = solrClient.query(collection, solrQuery, SolrRequest.METHOD.POST);

            // Convert results to Jackson-friendly, mutable structures
            List<Map<String, Object>> documents = new ArrayList<>();
            for (SolrDocument doc : response.getResults()) {
                // Apply minScore filter if specified (read via SolrDocument API)
                if (minScore != null) {
                    Object scoreObj = doc.getFieldValue("score");
                    if (scoreObj != null) {
                        double score = ((Number) scoreObj).doubleValue();
                        if (score < minScore) {
                            continue; // Skip documents below threshold
                        }
                    }
                }

                // Copy using SolrDocument API to avoid UnsupportedOperationException from entrySet()
                // Normalize metadata_* fields to top-level keys expected by API consumers (e.g., genre, tags)
                Map<String, Object> copy = new java.util.HashMap<>();
                for (String fieldName : doc.getFieldNames()) {
                    String outKey = fieldName;
                    if (fieldName.startsWith("metadata_")) {
                        outKey = fieldName.substring("metadata_".length());
                    }

                    Object v = doc.getFieldValue(fieldName);
                    if (v instanceof java.util.List<?> list) {
                        copy.put(outKey, new java.util.ArrayList<>(list));
                    } else if (v instanceof java.util.Map<?, ?> map) {
                        copy.put(outKey, new java.util.HashMap<>(map));
                    } else {
                        // Convert numeric types if necessary (e.g., Long to Integer for year)
                        if ("year".equals(outKey) && v instanceof Number num) {
                            // Preserve original if fits in int
                            copy.put(outKey, num.intValue());
                        } else {
                            copy.put(outKey, v);
                        }
                    }
                }

                documents.add(copy);
            }

            log.debug("Semantic search returned {} documents (before score filtering: {})",
                    documents.size(), response.getResults().size());

            return new SearchResponse(documents, Map.of());

        } catch (Exception e) {
            log.error("Semantic search failed for collection: {}, query: {}", collection, query, e);
            throw new RuntimeException("Semantic search failed: " + e.getMessage(), e);
        }
    }

}
