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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@Repository
public class SearchRepository {

    Logger log = LoggerFactory.getLogger(SearchRepository.class);
    private final SolrClient solrClient;
    private final EmbeddingService embeddingService;

    public SearchRepository(SolrClient solrClient, EmbeddingService embeddingService) {
        this.solrClient = solrClient;
        this.embeddingService = embeddingService;
    }

    public SearchResponse search(String collection, SearchRequest searchRequest) {
        SolrQuery query = new SolrQuery(searchRequest.query());

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

            return new SearchResponse(
                    response.getResults().stream()
                            .map(d -> new java.util.HashMap<String, Object>(d))
                            .collect(Collectors.toList()),
                    facetCountsMap
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Performs semantic search using vector similarity (KNN).
     *
     * @param collection     the Solr collection to search
     * @param queryVector    the query embedding vector
     * @param searchRequest  search parameters (filters, sort, fields, facets)
     * @param topK          number of nearest neighbors to return
     * @return search response with semantically similar documents
     * @deprecated This method is not used. SearchService uses VectorStore directly for semantic search.
     *             This method will be removed in a future version.
     */
    @Deprecated(since = "1.0.0", forRemoval = true)
    public SearchResponse semanticSearch(String collection, List<Float> queryVector, SearchRequest searchRequest, int topK) {
        log.debug("Performing semantic search in collection: {} with topK: {}", collection, topK);

        // Build KNN query using Solr's vector search syntax
        // Format: {!knn f=vector topK=10}[0.1, 0.2, 0.3, ...]
        String vectorString = embeddingService.formatVectorForSolr(queryVector);
        String knnQuery = SolrQueryUtils.buildKnnQuery(topK, vectorString);

        SolrQuery query = new SolrQuery(knnQuery);

        // Apply filter queries from search request
        if (searchRequest.filterQueries() != null) {
            searchRequest.filterQueries().forEach(query::addFilterQuery);
        }

        // Apply custom sort (default is by vector similarity score)
        if (searchRequest.hasSort()) {
            query.set("sort", searchRequest.sort());
        }

        // Set fields to return
        if (searchRequest.hasFieldList()) {
            query.setFields(searchRequest.fieldList());
        } else {
            // Default fields for semantic search
            query.setFields("*", "score");
        }

        // Handle faceting
        if (searchRequest.hasFacets()) {
            query.setFacet(true);
            searchRequest.facet().fields().forEach(query::addFacetField);
            if (searchRequest.facet().query() != null) {
                query.addFacetQuery(searchRequest.facet().query());
            }
        }

        try {
            // Use POST method to avoid URI Too Long errors with large vector embeddings
            QueryResponse response = solrClient.query(collection, query, SolrRequest.METHOD.POST);

            log.debug("Semantic search returned {} results", response.getResults().size());

            // Handle facet fields
            Map<String, List<SearchResponse.FacetCount>> facetCountsMap =
                response.getFacetFields() != null ?
                    response.getFacetFields().stream()
                            .collect(Collectors.toMap(
                                    f -> f.getName(),
                                    f -> f.getValues().stream()
                                            .map(c -> new SearchResponse.FacetCount(c.getName(), c.getCount()))
                                            .collect(Collectors.toList())))
                        : new java.util.HashMap<>();

            return new SearchResponse(
                    response.getResults().stream()
                            .map(d -> new java.util.HashMap<String, Object>(d))
                            .collect(Collectors.toList()),
                    facetCountsMap
            );
        } catch (Exception e) {
            log.error("Error performing semantic search in collection: {}", collection, e);
            throw new RuntimeException("Semantic search failed: " + e.getMessage(), e);
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
     * Executes hybrid search combining keyword and vector search using reranking.
     *
     * <p>This method implements hybrid search with intelligent fallback:
     * 1. Attempts hybrid search (keyword + vector reranking)
     * 2. If no results, falls back to keyword-only search
     * 3. If keyword fails, falls back to vector-only search
     * 4. Combines both signals using Solr's rerank query parser</p>
     *
     * <p>Note: Native RRF (Reciprocal Rank Fusion) is not yet available in Solr 9.10.0.
     * This implementation uses reranking as an alternative approach to combine keyword and vector search.</p>
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
                                                    String filterExpression,
                                                    String fieldsCsv,
                                                    Double minScore) {
        log.debug("Performing hybrid search in collection: {} with query: {}", collection, query);

        try {
            // Step 1: Generate embedding for the query text
            String vectorString = embeddingService.embedAndFormatForSolr(query);

            // Step 2: Build hybrid query with keyword search + vector reranking
            ModifiableSolrParams params = new ModifiableSolrParams();

            // Keyword search component - use edismax for flexible text matching
            params.set("q", query);
            params.set("defType", "edismax");
            params.set("qf", "_text_ content^2");  // Search in catch-all text field and boost content field

            // Vector search component using rerank to re-score top keyword results
            // reRankDocs: number of top docs from main query to rerank
            // reRankWeight: weight given to the rerank score (1.0 = equal weight)
            params.set("rq", "{!rerank reRankQuery=$vectorQ reRankDocs=200 reRankWeight=2.0}");
            params.set("vectorQ", SolrQueryUtils.buildKnnQuery(topK, vectorString));

            // Apply filter expression if present
            if (filterExpression != null && !filterExpression.isEmpty()) {
                params.set("fq", filterExpression);
            }

            // Set fields to return
            if (fieldsCsv != null && !fieldsCsv.isEmpty()) {
                params.set("fl", fieldsCsv + ",score");
            } else {
                params.set("fl", "*,score");
            }

            // Set number of rows
            params.set("rows", topK);

            // Step 3: Execute query using POST to avoid URI Too Long errors with large vector embeddings
            QueryResponse response = solrClient.query(collection, params, SolrRequest.METHOD.POST);

            log.debug("Hybrid search with reranking returned {} results", response.getResults().size());

            // Step 4: Convert results (apply minScore filter and handle multi-valued fields)
            List<Map<String, Object>> documents = response.getResults().stream()
                    .filter(doc -> {
                        if (minScore != null) {
                            Object scoreObj = doc.getFieldValue("score");
                            if (scoreObj instanceof Number) {
                                return ((Number) scoreObj).doubleValue() >= minScore;
                            }
                        }
                        return true;
                    })
                    .map(doc -> {
                        Map<String, Object> docMap = new java.util.HashMap<>();
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
                    })
                    .collect(Collectors.toList());

            // Handle facets
            Map<String, List<SearchResponse.FacetCount>> facetCountsMap =
                    response.getFacetFields() != null ?
                            response.getFacetFields().stream()
                                    .collect(Collectors.toMap(
                                            f -> f.getName(),
                                            f -> f.getValues().stream()
                                                    .map(c -> new SearchResponse.FacetCount(c.getName(), c.getCount()))
                                                    .collect(Collectors.toList())))
                            : new java.util.HashMap<>();

            // Step 5: Implement fallback if no results
            if (documents.isEmpty()) {
                log.warn("Hybrid search returned no results, attempting keyword-only fallback");
                return fallbackToKeywordSearch(collection, query, topK, filterExpression, fieldsCsv, minScore);
            }

            return new SearchResponse(documents, facetCountsMap);

        } catch (Exception e) {
            log.error("Error performing hybrid search with reranking in collection: {}", collection, e);
            log.warn("Hybrid search failed, attempting keyword-only fallback");
            return fallbackToKeywordSearch(collection, query, topK, filterExpression, fieldsCsv, minScore);
        }
    }

    /**
     * @deprecated Use {@link #executeHybridRerankSearch(String, String, int, String, String, Double)} instead.
     * This method will be removed in a future version.
     */
    @Deprecated(since = "1.0.0", forRemoval = true)
    public SearchResponse hybridSearch(String collection,
                                       String query,
                                       int topK,
                                       String filterExpression,
                                       String fieldsCsv,
                                       Double minScore) {
        return executeHybridRerankSearch(collection, query, topK, filterExpression, fieldsCsv, minScore);
    }

    /**
     * Fallback to keyword-only search when hybrid search fails or returns no results.
     * If keyword search also fails, attempts vector-only search as last resort.
     */
    private SearchResponse fallbackToKeywordSearch(String collection,
                                                   String query,
                                                   int topK,
                                                   String filterExpression,
                                                   String fieldsCsv,
                                                   Double minScore) {
        try {
            log.debug("Attempting keyword-only search fallback");
            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set("q", query);
            params.set("defType", "edismax");
            params.set("qf", "_text_ content^2");

            if (filterExpression != null && !filterExpression.isEmpty()) {
                params.set("fq", filterExpression);
            }

            if (fieldsCsv != null && !fieldsCsv.isEmpty()) {
                params.set("fl", fieldsCsv + ",score");
            } else {
                params.set("fl", "*,score");
            }

            params.set("rows", topK);

            QueryResponse response = solrClient.query(collection, params, SolrRequest.METHOD.POST);

            List<Map<String, Object>> documents = response.getResults().stream()
                    .filter(doc -> {
                        if (minScore != null) {
                            Object scoreObj = doc.getFieldValue("score");
                            if (scoreObj instanceof Number) {
                                return ((Number) scoreObj).doubleValue() >= minScore;
                            }
                        }
                        return true;
                    })
                    .map(doc -> {
                        Map<String, Object> docMap = new java.util.HashMap<>();
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
                    })
                    .collect(Collectors.toList());

            if (documents.isEmpty()) {
                log.warn("Keyword search fallback returned no results, attempting vector-only search");
                return fallbackToVectorSearch(collection, query, topK, filterExpression, fieldsCsv, minScore);
            }

            log.info("Keyword-only fallback succeeded with {} results", documents.size());
            return new SearchResponse(documents, new java.util.HashMap<>());

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
                                                  String filterExpression,
                                                  String fieldsCsv,
                                                  Double minScore) {
        try {
            log.debug("Attempting vector-only search fallback");
            String vectorString = embeddingService.embedAndFormatForSolr(query);

            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set("q", SolrQueryUtils.buildKnnQuery(topK, vectorString));

            if (filterExpression != null && !filterExpression.isEmpty()) {
                params.set("fq", filterExpression);
            }

            if (fieldsCsv != null && !fieldsCsv.isEmpty()) {
                params.set("fl", fieldsCsv + ",score");
            } else {
                params.set("fl", "*,score");
            }

            params.set("rows", topK);

            QueryResponse response = solrClient.query(collection, params, SolrRequest.METHOD.POST);

            List<Map<String, Object>> documents = response.getResults().stream()
                    .filter(doc -> {
                        if (minScore != null) {
                            Object scoreObj = doc.getFieldValue("score");
                            if (scoreObj instanceof Number) {
                                return ((Number) scoreObj).doubleValue() >= minScore;
                            }
                        }
                        return true;
                    })
                    .map(doc -> {
                        Map<String, Object> docMap = new java.util.HashMap<>();
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
                    })
                    .collect(Collectors.toList());

            log.info("Vector-only fallback returned {} results", documents.size());
            return new SearchResponse(documents, new java.util.HashMap<>());

        } catch (Exception e) {
            log.error("All search strategies failed (hybrid, keyword, vector)", e);
            // Return empty results rather than throwing exception
            return new SearchResponse(new java.util.ArrayList<>(), new java.util.HashMap<>());
        }
    }

    /**
     * Fetches field schema information from Solr including field types and attributes
     */
    public List<FieldInfo> getFieldsWithSchema(String collection) {
        List<FieldInfo> fieldInfos = new ArrayList<>();

        try {
            // Get all fields from schema
            SchemaRequest.Fields fieldsRequest = new SchemaRequest.Fields();
            SchemaResponse.FieldsResponse fieldsResponse = fieldsRequest.process(solrClient, collection);
            List<Map<String, Object>> fields = fieldsResponse.getFields();

            // Get actually used fields from documents
            Set<String> usedFields = getActuallyUsedFields(collection);

            // Filter to only include fields that are actually used in documents
            for (Map<String, Object> field : fields) {
                String name = (String) field.get("name");

                // Skip internal Solr fields
                if (name.startsWith("_")) {
                    continue;
                }

                // Only include fields that are actually used in documents
                if (!usedFields.contains(name)) {
                    continue;
                }

                String type = (String) field.get("type");
                boolean multiValued = Boolean.TRUE.equals(field.get("multiValued"));
                boolean stored = !Boolean.FALSE.equals(field.get("stored")); // default is true
                boolean docValues = Boolean.TRUE.equals(field.get("docValues"));
                boolean indexed = !Boolean.FALSE.equals(field.get("indexed")); // default is true

                fieldInfos.add(new FieldInfo(name, type, multiValued, stored, docValues, indexed));
            }

            log.debug("Retrieved {} fields with schema information from collection {}", fieldInfos.size(), collection);

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
            solrQuery.setQuery(SolrQueryUtils.buildKnnQuery("vector", topK, vectorString));
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
                solrQuery.setFields("*", "score");
            }

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
                            copy.put(outKey, Integer.valueOf(num.intValue()));
                        } else {
                            copy.put(outKey, v);
                        }
                    }
                }

                documents.add(copy);
            }

            log.debug("Semantic search returned {} documents (before score filtering: {})",
                    documents.size(), response.getResults().size());

            return new SearchResponse(documents, new java.util.HashMap<>());

        } catch (Exception e) {
            log.error("Semantic search failed for collection: {}, query: {}", collection, query, e);
            throw new RuntimeException("Semantic search failed: " + e.getMessage(), e);
        }
    }
}
