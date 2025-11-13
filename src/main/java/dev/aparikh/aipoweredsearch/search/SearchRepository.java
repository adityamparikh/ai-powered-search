package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.embedding.EmbeddingService;
import dev.aparikh.aipoweredsearch.search.model.FieldInfo;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.schema.SchemaResponse;
import org.apache.solr.common.SolrDocument;
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
                                            .toList()))
                    : Map.of();

            return new SearchResponse(
                    response.getResults().stream().map(d -> (Map<String, Object>) d).toList(),
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
     */
    public SearchResponse semanticSearch(String collection, List<Float> queryVector, SearchRequest searchRequest, int topK) {
        log.debug("Performing semantic search in collection: {} with topK: {}", collection, topK);

        // Build KNN query using Solr's vector search syntax
        // Format: {!knn f=vector topK=10}[0.1, 0.2, 0.3, ...]
        String vectorString = embeddingService.formatVectorForSolr(queryVector);

        String knnQuery = String.format("{!knn f=vector topK=%d}%s", topK, vectorString);

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
            QueryResponse response = solrClient.query(collection, query);

            log.debug("Semantic search returned {} results", response.getResults().size());

            // Handle facet fields
            Map<String, List<SearchResponse.FacetCount>> facetCountsMap =
                response.getFacetFields() != null ?
                    response.getFacetFields().stream()
                            .collect(Collectors.toMap(
                                    f -> f.getName(),
                                    f -> f.getValues().stream()
                                            .map(c -> new SearchResponse.FacetCount(c.getName(), c.getCount()))
                                            .toList()))
                    : Map.of();

            return new SearchResponse(
                    response.getResults().stream().map(d -> (Map<String, Object>) d).toList(),
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
     * Performs hybrid search using Reciprocal Rank Fusion (RRF).
     *
     * <p>This method combines traditional keyword search with semantic vector search
     * using Solr's RRF capabilities to provide better search results.</p>
     *
     * @param collection        the Solr collection to search
     * @param query             the text query for keyword search
     * @param topK              number of results to return from vector search
     * @param filterExpression  optional filter query
     * @param fieldsCsv         optional comma-separated list of fields to return
     * @param minScore          optional minimum score threshold
     * @return search response with RRF-ranked documents
     */
    public SearchResponse hybridSearch(String collection,
                                       String query,
                                       int topK,
                                       String filterExpression,
                                       String fieldsCsv,
                                       Double minScore) {
        log.debug("Performing hybrid search in collection: {} with query: {}", collection, query);

        try {
            // Generate embedding for the query text and format for Solr
            String vectorString = embeddingService.embedAndFormatForSolr(query);

            // Create SolrQuery with RRF parameters
            SolrQuery solrQuery = new SolrQuery();

            // Main query: keyword search using edismax
            solrQuery.set("q", "{!edismax qf='content'}(" + query + ")");

            // Re-rank query: KNN vector search
            solrQuery.set("rq", "{!rerank reRankQuery=$rqq reRankDocs=" + topK + " reRankWeight=1}");
            solrQuery.set("rqq", "{!knn f=vector topK=" + topK + "}" + vectorString);

            // Enable RRF
            solrQuery.set("rrf", "true");
            solrQuery.set("rrf.queryFields", "q,rqq");

            // Apply filter expression if present
            if (filterExpression != null && !filterExpression.isEmpty()) {
                solrQuery.addFilterQuery(filterExpression);
            }

            // Set fields to return
            if (fieldsCsv != null && !fieldsCsv.isEmpty()) {
                solrQuery.setFields(fieldsCsv.split(","));
            } else {
                solrQuery.setFields("*", "score");
            }

            // Set number of rows to return
            solrQuery.setRows(topK);

            // Execute query
            QueryResponse response = solrClient.query(collection, solrQuery);

            log.debug("Hybrid search returned {} results", response.getResults().size());

            // Convert results to SearchResponse format
            List<Map<String, Object>> documents = response.getResults().stream()
                    .filter(doc -> {
                        // Apply minimum score filter if specified
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
                            // Extract first value from multi-valued fields
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
                    .toList();

            // Handle facet fields - not typically used with RRF but included for completeness
            Map<String, List<SearchResponse.FacetCount>> facetCountsMap =
                    response.getFacetFields() != null ?
                            response.getFacetFields().stream()
                                    .collect(Collectors.toMap(
                                            f -> f.getName(),
                                            f -> f.getValues().stream()
                                                    .map(c -> new SearchResponse.FacetCount(c.getName(), c.getCount()))
                                                    .toList()))
                            : Map.of();

            return new SearchResponse(documents, facetCountsMap);

        } catch (Exception e) {
            log.error("Error performing hybrid search in collection: {}", collection, e);
            throw new RuntimeException("Hybrid search failed: " + e.getMessage(), e);
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
}
