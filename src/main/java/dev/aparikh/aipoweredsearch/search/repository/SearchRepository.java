package dev.aparikh.aipoweredsearch.search.repository;

import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@Repository
public class SearchRepository {

    Logger log = LoggerFactory.getLogger(SearchRepository.class);
    private final SolrClient solrClient;

    SearchRepository(SolrClient solrClient) {
        this.solrClient = solrClient;
    }

    public SearchResponse search(String collection, SearchRequest searchRequest) {
        SolrQuery query = new SolrQuery(searchRequest.getQuery());

        if (searchRequest.getFilterQueries() != null) {
            searchRequest.getFilterQueries().forEach(query::addFilterQuery);
        }

        if (searchRequest.hasSort()) {
            query.set("sort", searchRequest.getSort());
        }

        if (searchRequest.hasFacets()) {
            query.setFacet(true);
            searchRequest.getFacet().getFields().forEach(query::addFacetField);
            if (searchRequest.getFacet().getQuery() != null) {
                query.addFacetQuery(searchRequest.getFacet().getQuery());
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
                    : Map.of();
                    
            return new SearchResponse(
                    response.getResults().stream().map(d -> (Map<String, Object>) d).collect(Collectors.toList()),
                    facetCountsMap
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
}
