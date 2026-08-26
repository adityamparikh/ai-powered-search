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
import org.apache.solr.common.params.ModifiableSolrParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    private static final int OVER_FETCH_MULTIPLIER = 2;

    Logger log = LoggerFactory.getLogger(SearchRepository.class);
    private final SolrClient solrClient;
    private final EmbeddingService embeddingService;

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
    private void setupFieldList(ModifiableSolrParams params, String fieldsCsv) {
        if (fieldsCsv != null && !fieldsCsv.isEmpty()) {
            params.set("fl", fieldsCsv + "," + FIELD_SCORE);
        } else {
            params.set("fl", "*," + FIELD_SCORE);
        }
    }

    /**
     * Sets up filter query parameter if provided.
     */
    private void setupFilterQuery(ModifiableSolrParams params, String filterExpression) {
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
    private List<Map<String, Object>> filterByMinScore(List<Map<String, Object>> documents, Double minScore) {
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
    private boolean passesMinScore(Map<String, Object> doc, Double minScore) {
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
     * Executes hybrid search combining keyword and vector search using client-side RRF
     * (Reciprocal Rank Fusion).
     *
     * <p>This method runs keyword and vector searches <strong>concurrently</strong> and then
     * merges the results using the RRF algorithm. If the merged results are empty, it falls
     * back through a cascade: keyword-only, then vector-only.</p>
     *
     * <p>Execution flow:
     * <ol>
     *   <li>Launch keyword search (edismax) and vector search (KNN) in parallel</li>
     *   <li>Await both results</li>
     *   <li>Apply minScore to the vector results only, while their scores are still
     *       cosine similarities</li>
     *   <li>Merge using {@link RrfMerger}</li>
     *   <li>Limit to topK</li>
     *   <li>If empty, try keyword-only fallback, then vector-only fallback</li>
     * </ol>
     *
     * @param collection       the Solr collection to search
     * @param query            the text query for keyword search
     * @param topK             number of results to return
     * @param filterExpression optional filter query
     * @param fieldsCsv        optional comma-separated list of fields to return
     * @param minScore         optional minimum cosine similarity in {@code [0..1]}, applied to
     *                         the vector leg before fusion. It is deliberately not applied to
     *                         keyword results (BM25 is unbounded) nor to the fused output (RRF
     *                         scores are rank-derived, with a maximum of {@code 2/(k+1)}, so
     *                         comparing them against a similarity threshold is meaningless).
     * @return search response with hybrid-ranked documents
     */
    public SearchResponse executeHybridRerankSearch(String collection,
                                                    String query,
                                                    int topK,
                                                    String filterExpression,
                                                    String fieldsCsv,
                                                    Double minScore) {
        log.debug("Performing hybrid search (client-side RRF) in collection: {} with query: {}", collection, query);

        int fetchSize = topK * OVER_FETCH_MULTIPLIER;

        try {
            // The two legs are independent network round-trips on the critical path, so they run
            // concurrently: hybrid search then costs roughly one Solr round-trip instead of two.
            //
            // Virtual threads make blocking cheap, but cheap blocking is not concurrency — two
            // sequential calls on a virtual thread are still sequential. The overlap has to be
            // asked for explicitly.
            List<Map<String, Object>> keywordResults;
            List<Map<String, Object>> vectorResults;
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<List<Map<String, Object>>> keywordLeg = executor.submit(() ->
                        executeKeywordSearch(collection, query, fetchSize, filterExpression, fieldsCsv));
                Future<List<Map<String, Object>>> vectorLeg = executor.submit(() ->
                        executeVectorSearch(collection, query, fetchSize, filterExpression, fieldsCsv));

                // Either get() may throw ExecutionException; the surrounding catch degrades to
                // the fallback cascade exactly as it did when these ran inline.
                keywordResults = keywordLeg.get();
                vectorResults = vectorLeg.get();
            }

            log.debug("Keyword search returned {} results, vector search returned {} results",
                    keywordResults.size(), vectorResults.size());

            // Apply minScore to the vector leg only, before fusion, while the score is still
            // a cosine similarity in [0..1]. The keyword leg is left alone because BM25 is
            // unbounded, and the fused score is never thresholded because RRF scores are
            // rank-derived and carry no absolute meaning.
            vectorResults = filterByMinScore(vectorResults, minScore);

            // Merge using RRF
            RrfMerger rrfMerger = new RrfMerger();
            List<Map<String, Object>> mergedResults = rrfMerger.merge(keywordResults, vectorResults);
            log.debug("RRF merged results: {} unique documents", mergedResults.size());

            // Limit to topK (no score threshold — see above)
            List<Map<String, Object>> finalResults = mergedResults.stream()
                    .limit(topK)
                    .collect(Collectors.toList());

            log.debug("Final hybrid results after filtering and limiting: {} documents", finalResults.size());

            if (!finalResults.isEmpty()) {
                return new SearchResponse(finalResults, Map.of(), Map.of(), null);
            }

            // Fallback cascade
            log.warn("Hybrid search returned no results, attempting fallback");
            return fallbackSearch(collection, query, topK, filterExpression, fieldsCsv, minScore);

        } catch (Exception e) {
            log.error("Error performing hybrid search with RRF in collection: {}", collection, e);
            log.warn("Hybrid search failed, attempting fallback");
            return fallbackSearch(collection, query, topK, filterExpression, fieldsCsv, minScore);
        }
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
                                                   String filterExpression,
                                                   String fieldsCsv) throws Exception {
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
                                                  String filterExpression,
                                                  String fieldsCsv) throws Exception {
        String vectorString = embeddingService.embedAndFormatForSolr(query);

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
     * Fallback strategy when hybrid search produces no results.
     * Tries keyword-only first, then vector-only as a last resort.
     */
    private SearchResponse fallbackSearch(String collection,
                                          String query,
                                          int topK,
                                          String filterExpression,
                                          String fieldsCsv,
                                          Double minScore) {
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
                    collection, query, topK, filterExpression, fieldsCsv), minScore);

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
                    String type = (String) schemaField.get("type");
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
                    String type = (String) bestMatch.get("type");
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
