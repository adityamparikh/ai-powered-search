package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.embedding.EmbeddingService;
import dev.aparikh.aipoweredsearch.search.model.FieldInfo;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import dev.aparikh.aipoweredsearch.solr.SolrQueryUtils;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.request.json.JsonQueryRequest;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.schema.SchemaResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.ShardParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * Multiplier applied to topK when fetching from individual searches.
     * Over-fetching improves fusion quality by giving RRF more candidates.
     */
    static final int OVER_FETCH_MULTIPLIER = 2;

    // Solr combined query (server-side RRF) constants. See solr-config/conf/solrconfig.xml.
    static final String HYBRID_HANDLER = "/hybrid";
    static final String KEYWORD_LEG = "keyword";
    static final String VECTOR_LEG = "vector";
    static final String COMBINER = "combiner";
    static final String COMBINER_QUERY = "combiner.query";
    static final String COMBINER_ALGORITHM = "combiner.algorithm";
    static final String COMBINER_RRF = "rrf";
    private static final int HTTP_NOT_FOUND = 404;

    Logger log = LoggerFactory.getLogger(SearchRepository.class);
    private final SolrClient solrClient;
    private final EmbeddingService embeddingService;

    /** Collections already reported as lacking the /hybrid handler, so the warning is logged once. */
    private final Set<String> collectionsWithoutHybridHandler = ConcurrentHashMap.newKeySet();

    public SearchRepository(SolrClient solrClient, EmbeddingService embeddingService) {
        this.solrClient = solrClient;
        this.embeddingService = embeddingService;
    }

    /**
     * Converts a SolrDocument to a Map, handling multi-valued fields by extracting the first value.
     */
    private Map<String, Object> convertSolrDocumentToMap(SolrDocument doc) {
        Map<String, Object> docMap = new HashMap<>();
        for (String fieldName : doc.getFieldNames()) {
            Object value = doc.getFieldValue(fieldName);
            if (value instanceof List) {
                List<?> list = (List<?>) value;
                if (!list.isEmpty()) {
                    docMap.put(fieldName, list.get(0));
                }
            } else {
                docMap.put(fieldName, value);
            }
        }
        return docMap;
    }

    /**
     * Sets up field list parameter, automatically adding score field if not already present.
     */
    private void setupFieldList(ModifiableSolrParams params, @Nullable String fieldsCsv) {
        if (fieldsCsv != null && !fieldsCsv.isEmpty()) {
            params.set("fl", fieldsCsv + "," + FIELD_SCORE);
        } else {
            params.set("fl", "*," + FIELD_SCORE);
        }
    }

    /**
     * Sets up filter query parameter if provided.
     */
    private void setupFilterQuery(ModifiableSolrParams params, @Nullable String filterExpression) {
        if (filterExpression != null && !filterExpression.isEmpty()) {
            params.set("fq", filterExpression);
        }
    }

    /**
     * Returns only those documents whose {@code score} meets the threshold.
     *
     * <p>Intended for result sets whose score is a cosine similarity in {@code [0..1]}
     * (i.e. vector search). Do not apply this to keyword results, whose BM25 scores are
     * unbounded, or to RRF-fused results, whose scores are rank-derived.</p>
     *
     * @param documents the documents to filter
     * @param minScore  the threshold, or null to keep everything
     * @return the surviving documents
     */
    private List<Map<String, Object>> filterByMinScore(List<Map<String, Object>> documents, @Nullable Double minScore) {
        if (minScore == null) {
            return documents;
        }
        List<Map<String, Object>> filtered = documents.stream()
                .filter(doc -> passesMinScore(doc, minScore))
                .collect(Collectors.toList());
        log.debug("minScore={} filtered {} documents down to {}", minScore, documents.size(), filtered.size());
        return filtered;
    }

    /**
     * Filters documents by minimum score threshold.
     */
    private boolean passesMinScore(Map<String, Object> doc, @Nullable Double minScore) {
        if (minScore == null) {
            return true;
        }
        Object scoreObj = doc.get(FIELD_SCORE);
        if (scoreObj instanceof Number) {
            return ((Number) scoreObj).doubleValue() >= minScore;
        }
        return true;
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
            SearchRequest.Facet facet = searchRequest.facet();
            if (facet != null && facet.fields() != null) {
                query.setFacet(true);
                facet.fields().forEach(query::addFacetField);
                if (facet.query() != null) {
                    query.addFacetQuery(facet.query());
                }
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
                        : new java.util.HashMap<>();

            // Handle spell check suggestions
            SearchResponse.SpellCheckSuggestion spellCheckSuggestion = null;
            if (response.getSpellCheckResponse() != null &&
                    response.getSpellCheckResponse().getCollatedResult() != null) {
                String collation = response.getSpellCheckResponse().getCollatedResult();
                if (!collation.equals(searchRequest.query())) {
                    spellCheckSuggestion = new SearchResponse.SpellCheckSuggestion(collation, searchRequest.query());
                }
            }

            return new SearchResponse(
                    response.getResults().stream()
                            .map(this::convertSolrDocumentToMap)
                            .collect(Collectors.toList()),
                    facetCountsMap,
                    Map.of(), // No highlighting without knowing field names
                    spellCheckSuggestion
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
     * Executes hybrid search: a keyword (edismax) query and a vector (kNN) query fused with
     * Reciprocal Rank Fusion <strong>inside Solr</strong>, in a single request.
     *
     * <p>Uses Solr's combined query feature (Solr 10.1+, SOLR-17319): a JSON request whose
     * {@code queries} map names both legs, sent with {@code combiner=true} to the
     * {@link #HYBRID_HANDLER} handler ({@code solr.CombinedQuerySearchHandler}). Solr runs both
     * legs against the same filters, fuses their rankings with
     * {@code score = sum(1 / (k + rank))} and returns the fused order. Fusion happens per
     * collection, across shards, so ranks are global rather than per shard.</p>
     *
     * <p>Candidate depth: each leg contributes up to {@code shards.rows} candidates, set to
     * {@code topK * }{@value #OVER_FETCH_MULTIPLIER} so RRF sees more than the final page.</p>
     *
     * <p>Fallback: if the combined request fails (for example a collection whose configset
     * lacks the {@code /hybrid} handler, which Solr answers with 404) or the query cannot be
     * embedded, this degrades to keyword-only and then vector-only search.</p>
     *
     * <p><strong>Scores.</strong> Returned rows carry no {@code score}: Solr orders hits by
     * fused RRF score, but the per-document {@code score} it returns is the raw score from
     * one of the legs (BM25 or cosine), which is misleading next to a fused order.</p>
     *
     * @param collection       the Solr collection to search
     * @param query            the text query; used verbatim by the keyword leg and embedded for
     *                         the vector leg
     * @param topK             number of results to return
     * @param filterExpression optional Solr filter query, applied to both legs
     * @param fieldsCsv        optional comma-separated list of fields to return
     * @param minScore         optional minimum cosine similarity in {@code [0..1]}. When set,
     *                         the vector leg becomes a {@code vectorSimilarity} query with
     *                         {@code minReturn=minScore}, so only sufficiently similar
     *                         documents enter fusion. It is deliberately not applied to the
     *                         keyword leg (BM25 is unbounded) nor to the fused output (RRF
     *                         scores are rank-derived).
     * @return search response with hybrid-ranked documents
     */
    public SearchResponse executeHybridRerankSearch(String collection,
                                                    String query,
                                                    int topK,
                                                    @Nullable String filterExpression,
                                                    @Nullable String fieldsCsv,
                                                    @Nullable Double minScore) {
        log.debug("Performing hybrid search (Solr RRF) in collection: {} with query: {}", collection, query);

        // Kept outside the try so a failed combined request does not pay for a second embedding
        // call in the vector-only fallback.
        @Nullable String vector = null;
        try {
            vector = embeddingService.embedAndFormatForSolr(query);
            JsonQueryRequest request = buildHybridRequest(
                    query, vector, topK, filterExpression, fieldsCsv, minScore);

            List<Map<String, Object>> results = request.process(solrClient, collection)
                    .getResults().stream()
                    .map(this::convertSolrDocumentToMap)
                    .collect(Collectors.toList());

            log.debug("Hybrid search (Solr RRF) returned {} documents", results.size());
            return new SearchResponse(results, Map.of(), Map.of(), null);

        } catch (Exception e) {
            if (isInterruption(e)) {
                // The caller is being cancelled: running fallback queries on this thread would ignore
                // that and swallow the interrupt. Restore the flag and propagate instead.
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Hybrid search was interrupted", e);
            }
            if (e instanceof SolrException solrException && solrException.code() == HTTP_NOT_FOUND) {
                // A configuration problem, not an outage: no stack trace, said once per collection.
                if (collectionsWithoutHybridHandler.add(collection)) {
                    log.warn("Collection '{}' has no {} handler (solr.CombinedQuerySearchHandler, Solr 10.1+); "
                            + "hybrid search is degrading to keyword/vector fallback. Add the handler to the "
                            + "collection's configset (see solr-config/conf/solrconfig.xml).",
                            collection, HYBRID_HANDLER);
                } else {
                    log.debug("Collection '{}' still has no {} handler; using fallback", collection, HYBRID_HANDLER);
                }
            } else {
                log.error("Error performing hybrid search with Solr RRF in collection: {}", collection, e);
                log.warn("Hybrid search failed, attempting fallback");
            }
            return fallbackSearch(collection, query, vector, topK, filterExpression, fieldsCsv, minScore);
        }
    }

    private static boolean isInterruption(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the combined-query JSON request for {@link #executeHybridRerankSearch}.
     * Public so diagnostics (e.g. the RAG evaluation harness) can re-issue the exact request
     * with extra parameters such as {@code debug=results}, which returns Solr's per-document
     * RRF explanation including each document's rank in each leg.
     *
     * <p>Equivalent JSON:</p>
     * <pre>{@code
     * POST /solr/{collection}/hybrid
     * {
     *   "queries": {
     *     "keyword": { "edismax": { "query": "...", "qf": "_text_" } },
     *     "vector":  { "knn": { "f": "vector", "topK": 2*topK, "query": "[...]" } }
     *   },
     *   "limit": topK,
     *   "fields": [...],
     *   "filter": [...],
     *   "params": {
     *     "combiner": true,
     *     "combiner.query": ["keyword", "vector"],
     *     "combiner.algorithm": "rrf",
     *     "shards.rows": 2*topK
     *   }
     * }
     * }</pre>
     */
    public JsonQueryRequest buildHybridRequest(String query,
                                               String vector,
                                               int topK,
                                               @Nullable String filterExpression,
                                               @Nullable String fieldsCsv,
                                               @Nullable Double minScore) {
        int fetchSize = topK * OVER_FETCH_MULTIPLIER;

        Map<String, Object> keywordLeg = Map.of(QUERY_TYPE_EDISMAX, Map.of(
                "query", query,
                "qf", FIELD_TEXT_CATCHALL));

        Map<String, Object> vectorLeg = minScore == null
                ? Map.of("knn", Map.of(
                        "f", FIELD_VECTOR,
                        // Same as shards.rows below: kNN should find as many candidates as the
                        // combiner will consider from this leg.
                        "topK", fetchSize,
                        "query", vector))
                : Map.of("vectorSimilarity", Map.of(
                        "f", FIELD_VECTOR,
                        "minReturn", minScore,
                        "query", vector));

        Map<String, Object> queries = new LinkedHashMap<>();
        queries.put(KEYWORD_LEG, keywordLeg);
        queries.put(VECTOR_LEG, vectorLeg);

        JsonQueryRequest request = new JsonQueryRequest()
                .setQueries(queries)
                .setLimit(topK)
                .withParam(COMBINER, true)
                .withParam(COMBINER_QUERY, List.of(KEYWORD_LEG, VECTOR_LEG))
                .withParam(COMBINER_ALGORITHM, COMBINER_RRF)
                // Per-leg candidate depth is max(start + rows, shards.rows)
                .withParam(ShardParams.SHARDS_ROWS, fetchSize);
        request.setPath(HYBRID_HANDLER);

        if (filterExpression != null && !filterExpression.isEmpty()) {
            request.withFilter(filterExpression);
        }
        if (fieldsCsv != null && !fieldsCsv.isEmpty()) {
            request.returnFields(Arrays.stream(fieldsCsv.split(","))
                    .map(String::trim)
                    .filter(f -> !f.isEmpty())
                    .toList());
        }
        return request;
    }

    /**
     * Executes keyword-only search using edismax query parser.
     *
     * @param collection       the Solr collection
     * @param query            the text query
     * @param rows             number of results to return
     * @param filterExpression optional filter query
     * @param fieldsCsv        optional comma-separated list of fields to return
     * @return list of documents with scores
     */
    List<Map<String, Object>> executeKeywordSearch(String collection,
                                                   String query,
                                                   int rows,
                                                   @Nullable String filterExpression,
                                                   @Nullable String fieldsCsv) throws Exception {
        ModifiableSolrParams params = new ModifiableSolrParams();
        params.set("q", query);
        params.set("defType", QUERY_TYPE_EDISMAX);
        params.set("qf", FIELD_TEXT_CATCHALL);
        params.set("rows", rows);

        setupFilterQuery(params, filterExpression);
        setupFieldList(params, fieldsCsv);

        QueryResponse response = solrClient.query(collection, params, SolrRequest.METHOD.POST);

        return response.getResults().stream()
                .map(this::convertSolrDocumentToMap)
                .collect(Collectors.toList());
    }

    /**
     * Executes vector-only search using KNN query.
     *
     * @param collection       the Solr collection
     * @param query            the text query (will be embedded)
     * @param rows             number of results to return
     * @param filterExpression optional filter query
     * @param fieldsCsv        optional comma-separated list of fields to return
     * @return list of documents with similarity scores
     */
    List<Map<String, Object>> executeVectorSearch(String collection,
                                                  String query,
                                                  int rows,
                                                  @Nullable String filterExpression,
                                                  @Nullable String fieldsCsv) throws Exception {
        return executeVectorSearch(collection, query, null, rows, filterExpression, fieldsCsv);
    }

    private List<Map<String, Object>> executeVectorSearch(String collection,
                                                          String query,
                                                          @Nullable String precomputedVector,
                                                          int rows,
                                                          @Nullable String filterExpression,
                                                          @Nullable String fieldsCsv) throws Exception {
        String vectorString = precomputedVector != null
                ? precomputedVector
                : embeddingService.embedAndFormatForSolr(query);

        ModifiableSolrParams params = new ModifiableSolrParams();
        params.set("q", SolrQueryUtils.buildKnnQuery(FIELD_VECTOR, rows, vectorString));
        params.set("rows", rows);

        setupFilterQuery(params, filterExpression);
        setupFieldList(params, fieldsCsv);

        QueryResponse response = solrClient.query(collection, params, SolrRequest.METHOD.POST);

        return response.getResults().stream()
                .map(this::convertSolrDocumentToMap)
                .collect(Collectors.toList());
    }

    /**
     * Fallback strategy when the combined (Solr RRF) request fails.
     * Tries keyword-only first, then vector-only as a last resort.
     */
    private SearchResponse fallbackSearch(String collection,
                                          String query,
                                          @Nullable String vector,
                                          int topK,
                                          @Nullable String filterExpression,
                                          @Nullable String fieldsCsv,
                                          @Nullable Double minScore) {
        // Try keyword-only first. minScore is NOT applied here: BM25 scores are unbounded,
        // so a [0..1] threshold would silently discard perfectly good keyword matches.
        try {
            List<Map<String, Object>> keywordResults = executeKeywordSearch(
                    collection, query, topK, filterExpression, fieldsCsv);

            if (!keywordResults.isEmpty()) {
                log.info("Keyword-only fallback returned {} results", keywordResults.size());
                return new SearchResponse(keywordResults, Map.of(), Map.of(), null);
            }
        } catch (Exception e) {
            log.error("Keyword fallback failed", e);
        }

        // Try vector-only as last resort. minScore applies here — these are cosine similarities.
        try {
            List<Map<String, Object>> vectorResults = filterByMinScore(executeVectorSearch(
                    collection, query, vector, topK, filterExpression, fieldsCsv), minScore);

            if (!vectorResults.isEmpty()) {
                log.info("Vector-only fallback returned {} results", vectorResults.size());
                return new SearchResponse(vectorResults, Map.of(), Map.of(), null);
            }
        } catch (Exception e) {
            log.error("Vector fallback failed", e);
        }

        log.warn("All search strategies returned no results for query: {}", query);
        return new SearchResponse(new ArrayList<>(), Map.of(), Map.of(), null);
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
                    String type = schemaField.get("type") instanceof String s ? s : "unknown";
                    boolean multiValued = Boolean.TRUE.equals(schemaField.get("multiValued"));
                    boolean stored = !Boolean.FALSE.equals(schemaField.get("stored")); // default true
                    boolean docValues = Boolean.TRUE.equals(schemaField.get("docValues"));
                    boolean indexed = !Boolean.FALSE.equals(schemaField.get("indexed")); // default true
                    fieldInfos.add(new FieldInfo(fieldName, type, multiValued, stored, docValues, indexed));
                    continue;
                }

                // Try to match a dynamic field pattern
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

                if (bestMatch != null) {
                    String type = bestMatch.get("type") instanceof String s ? s : "unknown";
                    boolean multiValued = Boolean.TRUE.equals(bestMatch.get("multiValued"));
                    boolean stored = !Boolean.FALSE.equals(bestMatch.get("stored")); // default true
                    boolean docValues = Boolean.TRUE.equals(bestMatch.get("docValues"));
                    boolean indexed = !Boolean.FALSE.equals(bestMatch.get("indexed")); // default true
                    fieldInfos.add(new FieldInfo(fieldName, type, multiValued, stored, docValues, indexed));
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

}
