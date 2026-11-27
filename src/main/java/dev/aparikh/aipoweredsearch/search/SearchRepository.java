package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.embedding.EmbeddingService;
import dev.aparikh.aipoweredsearch.search.model.FieldInfo;
import dev.aparikh.aipoweredsearch.search.model.SearchRequest;
import dev.aparikh.aipoweredsearch.search.model.SearchResponse;
import dev.aparikh.aipoweredsearch.solr.SolrQueryUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.request.json.JsonQueryRequest;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.ShardParams;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Contract;
import org.springframework.stereotype.Repository;

@Repository
public class SearchRepository {

  // Essential field name constants (required for vector search)
  private static final String FIELD_VECTOR = "vector";
  private static final String FIELD_SCORE = "score";
  private static final String FIELD_TEXT_CATCHALL = "_text_";

  // Query type constants
  private static final String WILDCARD_QUERY = "*:*";
  private static final String QUERY_TYPE_EDISMAX = "edismax";

  // Solr request parameter names
  private static final String PARAM_QUERY = "q";
  private static final String PARAM_DEF_TYPE = "defType";
  private static final String PARAM_QUERY_FIELDS = "qf";
  private static final String PARAM_ROWS = "rows";
  private static final String PARAM_FIELD_LIST = "fl";
  private static final String PARAM_FILTER_QUERY = "fq";
  private static final String PARAM_SORT = "sort";

  // Schema attribute names, as returned by the Schema API
  private static final String SCHEMA_NAME = "name";
  private static final String SCHEMA_TYPE = "type";
  private static final String UNKNOWN_TYPE = "unknown";
  private static final String INTERNAL_FIELD_PREFIX = "_";
  private static final String DYNAMIC_FIELD_WILDCARD = "*";

  /** Number of documents sampled to discover which fields a collection actually uses. */
  private static final int FIELD_SAMPLE_SIZE = 100;

  /** Returned by {@link #dynamicMatchScore} when a field name does not match a pattern. */
  private static final int NO_MATCH = -1;

  /**
   * Multiplier applied to topK when fetching from individual searches. Over-fetching improves
   * fusion quality by giving RRF more candidates.
   */
  static final int OVER_FETCH_MULTIPLIER = 2;

  /** Upper bound on {@link #collectionsWithoutHybridHandler}. */
  static final int MAX_TRACKED_COLLECTIONS = 256;

  // Solr combined query (server-side RRF) constants. See solr-config/conf/solrconfig.xml.
  static final String HYBRID_HANDLER = "/hybrid";
  static final String KEYWORD_LEG = "keyword";
  static final String VECTOR_LEG = "vector";
  static final String COMBINER = "combiner";
  static final String COMBINER_QUERY = "combiner.query";
  static final String COMBINER_ALGORITHM = "combiner.algorithm";
  static final String COMBINER_RRF = "rrf";
  private static final int HTTP_NOT_FOUND = 404;

  private static final Logger log = LoggerFactory.getLogger(SearchRepository.class);

  private final SolrClient solrClient;
  private final EmbeddingService embeddingService;

  /**
   * Collections already reported as lacking the /hybrid handler, so the warning is logged once.
   * Bounded: collection names come from request paths, and Solr also answers 404 for a collection
   * that does not exist, so arbitrary names must not grow this set without limit.
   */
  private final Set<String> collectionsWithoutHybridHandler = ConcurrentHashMap.newKeySet();

  public SearchRepository(SolrClient solrClient, EmbeddingService embeddingService) {
    this.solrClient = solrClient;
    this.embeddingService = embeddingService;
  }

  /**
   * Converts a SolrDocument to a Map, handling multi-valued fields by extracting the first value.
   */
  private static Map<String, Object> toFirstValueMap(SolrDocument doc) {
    Map<String, Object> docMap = new HashMap<>();
    for (String fieldName : doc.getFieldNames()) {
      Object value = doc.getFieldValue(fieldName);
      if (value instanceof List<?> values) {
        if (!values.isEmpty()) {
          docMap.put(fieldName, values.get(0));
        }
      } else {
        docMap.put(fieldName, value);
      }
    }
    return docMap;
  }

  private static List<Map<String, Object>> toDocumentMaps(QueryResponse response) {
    return response.getResults().stream().map(SearchRepository::toFirstValueMap).toList();
  }

  private static SearchResponse documentsOnly(List<Map<String, Object>> documents) {
    return new SearchResponse(documents, Map.of(), Map.of(), null);
  }

  @Contract("null -> false")
  private static boolean hasText(@Nullable String value) {
    return value != null && !value.isEmpty();
  }

  /** Sets the field list parameter, always adding the score field. */
  private static void setFieldList(ModifiableSolrParams params, @Nullable String fieldsCsv) {
    String fields = hasText(fieldsCsv) ? fieldsCsv : DYNAMIC_FIELD_WILDCARD;
    params.set(PARAM_FIELD_LIST, fields + "," + FIELD_SCORE);
  }

  /** Sets the filter query parameter if one is provided. */
  private static void setFilterQuery(
      ModifiableSolrParams params, @Nullable String filterExpression) {
    if (hasText(filterExpression)) {
      params.set(PARAM_FILTER_QUERY, filterExpression);
    }
  }

  /**
   * Returns only those documents whose {@code score} meets the threshold.
   *
   * <p>Intended for result sets whose score is a cosine similarity in {@code [0..1]} (i.e. vector
   * search). Do not apply this to keyword results, whose BM25 scores are unbounded, or to RRF-fused
   * results, whose scores are rank-derived.
   *
   * @param documents the documents to filter
   * @param minScore the threshold, or null to keep everything
   * @return the surviving documents
   */
  private List<Map<String, Object>> filterByMinScore(
      List<Map<String, Object>> documents, @Nullable Double minScore) {
    if (minScore == null) {
      return documents;
    }
    List<Map<String, Object>> filtered =
        documents.stream().filter(doc -> meetsMinScore(doc, minScore)).toList();
    log.debug(
        "minScore={} filtered {} documents down to {}",
        minScore,
        documents.size(),
        filtered.size());
    return filtered;
  }

  /** True if the document's score meets the threshold; documents without a score are kept. */
  private static boolean meetsMinScore(Map<String, Object> doc, double minScore) {
    return !(doc.get(FIELD_SCORE) instanceof Number score) || score.doubleValue() >= minScore;
  }

  public SearchResponse search(String collection, SearchRequest searchRequest) {
    SolrQuery query = buildKeywordQuery(searchRequest);
    try {
      QueryResponse response = solrClient.query(collection, query);
      return new SearchResponse(
          toDocumentMaps(response),
          toFacetCounts(response),
          Map.of(), // No highlighting without knowing field names
          spellCheckSuggestion(response, searchRequest.query()));
    } catch (SolrServerException | IOException | SolrException e) {
      throw new IllegalStateException("Search failed in collection " + collection, e);
    }
  }

  private static SolrQuery buildKeywordQuery(SearchRequest searchRequest) {
    SolrQuery query = new SolrQuery(searchRequest.query());

    // edismax over the catch-all field works for any schema; a wildcard needs no query parser.
    if (!WILDCARD_QUERY.equals(searchRequest.query())) {
      query.set(PARAM_DEF_TYPE, QUERY_TYPE_EDISMAX);
      query.set(PARAM_QUERY_FIELDS, FIELD_TEXT_CATCHALL);
    }
    if (searchRequest.filterQueries() != null) {
      searchRequest.filterQueries().forEach(query::addFilterQuery);
    }
    if (searchRequest.hasSort()) {
      query.set(PARAM_SORT, searchRequest.sort());
    }
    if (searchRequest.hasFieldList()) {
      query.setFields(searchRequest.fieldList());
    }
    addFacets(query, searchRequest);
    addSpellCheck(query, searchRequest.query());
    return query;
  }

  private static void addFacets(SolrQuery query, SearchRequest searchRequest) {
    SearchRequest.Facet facet = searchRequest.facet();
    if (!searchRequest.hasFacets() || facet == null || facet.fields() == null) {
      return;
    }
    query.setFacet(true);
    facet.fields().forEach(query::addFacetField);
    if (facet.query() != null) {
      query.addFacetQuery(facet.query());
    }
  }

  /** Spell checking works without knowing specific fields. */
  private static void addSpellCheck(SolrQuery query, String userQuery) {
    query.setParam("spellcheck", "true");
    query.setParam("spellcheck.q", userQuery);
    query.setParam("spellcheck.collate", "true");
  }

  private static Map<String, List<SearchResponse.FacetCount>> toFacetCounts(
      QueryResponse response) {
    if (response.getFacetFields() == null) {
      return new HashMap<>();
    }
    return response.getFacetFields().stream()
        .collect(
            Collectors.toMap(
                FacetField::getName,
                field ->
                    field.getValues().stream()
                        .map(
                            count ->
                                new SearchResponse.FacetCount(count.getName(), count.getCount()))
                        .toList()));
  }

  private static SearchResponse.@Nullable SpellCheckSuggestion spellCheckSuggestion(
      QueryResponse response, String userQuery) {
    if (response.getSpellCheckResponse() == null) {
      return null;
    }
    String collation = response.getSpellCheckResponse().getCollatedResult();
    if (collation == null || collation.equals(userQuery)) {
      return null;
    }
    return new SearchResponse.SpellCheckSuggestion(collation, userQuery);
  }

  public Set<String> getActuallyUsedFields(String collection) {
    Set<String> usedFields = new HashSet<>();

    try {
      SolrQuery query = new SolrQuery(WILDCARD_QUERY);
      query.setRows(FIELD_SAMPLE_SIZE);

      QueryResponse response = solrClient.query(collection, query);

      for (SolrDocument doc : response.getResults()) {
        usedFields.addAll(doc.getFieldNames());
      }
    } catch (SolrServerException | IOException | SolrException e) {
      // Field discovery only feeds query generation hints, so an empty set is a safe answer.
      log.error("Error analyzing fields in collection {}", collection, e);
    }
    return usedFields;
  }

  /**
   * Executes hybrid search: a keyword (edismax) query and a vector (kNN) query fused with
   * Reciprocal Rank Fusion <strong>inside Solr</strong>, in a single request.
   *
   * <p>Uses Solr's combined query feature (Solr 10.1+, SOLR-17319): a JSON request whose {@code
   * queries} map names both legs, sent with {@code combiner=true} to the {@link #HYBRID_HANDLER}
   * handler ({@code solr.CombinedQuerySearchHandler}). Solr runs both legs against the same
   * filters, fuses their rankings with {@code score = sum(1 / (k + rank))} and returns the fused
   * order. Fusion happens per collection, across shards, so ranks are global rather than per shard.
   *
   * <p>Candidate depth: each leg contributes up to {@code shards.rows} candidates, set to {@code
   * topK * }{@value #OVER_FETCH_MULTIPLIER} so RRF sees more than the final page.
   *
   * <p>Fallback: if the combined request fails (for example a collection whose configset lacks the
   * {@code /hybrid} handler, which Solr answers with 404) or the query cannot be embedded, this
   * degrades to keyword-only and then vector-only search.
   *
   * <p><strong>Scores.</strong> Returned rows carry no {@code score}: Solr orders hits by fused RRF
   * score, but the per-document {@code score} it returns is the raw score from one of the legs
   * (BM25 or cosine), which is misleading next to a fused order.
   *
   * @param collection the Solr collection to search
   * @param query the text query; used verbatim by the keyword leg and embedded for the vector leg
   * @param topK number of results to return
   * @param filterExpression optional Solr filter query, applied to both legs
   * @param fieldsCsv optional comma-separated list of fields to return
   * @param minScore optional minimum cosine similarity in {@code [0..1]}. When set, the vector leg
   *     becomes a {@code vectorSimilarity} query with {@code minReturn=minScore}, so only
   *     sufficiently similar documents enter fusion. It is deliberately not applied to the keyword
   *     leg (BM25 is unbounded) nor to the fused output (RRF scores are rank-derived).
   * @return search response with hybrid-ranked documents
   */
  public SearchResponse executeHybridRerankSearch(
      String collection,
      String query,
      int topK,
      @Nullable String filterExpression,
      @Nullable String fieldsCsv,
      @Nullable Double minScore) {
    log.debug(
        "Performing hybrid search (Solr RRF) in collection: {} with query: {}", collection, query);

    // Kept outside the try so a failed combined request does not pay for a second embedding
    // call in the vector-only fallback.
    @Nullable String vector = null;
    try {
      vector = embeddingService.embedAndFormatForSolr(query);
      JsonQueryRequest request =
          buildHybridRequest(query, vector, topK, filterExpression, fieldsCsv, minScore);

      List<Map<String, Object>> results = toDocumentMaps(request.process(solrClient, collection));

      log.debug("Hybrid search (Solr RRF) returned {} documents", results.size());
      return documentsOnly(results);

    } catch (Exception e) {
      // Deliberately broad: any failure (Solr, I/O, embedding) degrades to the fallback.
      if (isInterruption(e)) {
        // The caller is being cancelled: running fallback queries on this thread would ignore
        // that and swallow the interrupt. Restore the flag and propagate instead.
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Hybrid search was interrupted", e);
      }
      logHybridFailure(collection, e);
      return fallbackSearch(collection, query, vector, topK, filterExpression, fieldsCsv, minScore);
    }
  }

  private void logHybridFailure(String collection, Exception e) {
    if (!(e instanceof SolrException solrException && solrException.code() == HTTP_NOT_FOUND)) {
      log.error("Error performing hybrid search with Solr RRF in collection: {}", collection, e);
      log.warn("Hybrid search failed, attempting fallback");
      return;
    }
    // A configuration problem, not an outage: no stack trace, said once per collection.
    if (firstMissingHandlerReport(collection)) {
      log.warn(
          "Collection '{}' has no {} handler (solr.CombinedQuerySearchHandler, Solr 10.1+); "
              + "hybrid search is degrading to keyword/vector fallback. Add the handler to the "
              + "collection's configset (see solr-config/conf/solrconfig.xml).",
          collection,
          HYBRID_HANDLER);
    } else {
      log.debug(
          "Collection '{}' still has no {} handler; using fallback", collection, HYBRID_HANDLER);
    }
  }

  int trackedMissingHandlerCollections() {
    return collectionsWithoutHybridHandler.size();
  }

  private boolean firstMissingHandlerReport(String collection) {
    if (collectionsWithoutHybridHandler.contains(collection)) {
      return false;
    }
    // Past the cap, keep warning rather than remembering more names.
    return collectionsWithoutHybridHandler.size() >= MAX_TRACKED_COLLECTIONS
        || collectionsWithoutHybridHandler.add(collection);
  }

  private static boolean isInterruption(Throwable e) {
    for (@Nullable Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof InterruptedException) {
        return true;
      }
    }
    return false;
  }

  /**
   * Builds the combined-query JSON request for {@link #executeHybridRerankSearch}. Public so
   * diagnostics (e.g. the RAG evaluation harness) can re-issue the exact request with extra
   * parameters such as {@code debug=results}, which returns Solr's per-document RRF explanation
   * including each document's rank in each leg.
   *
   * <p>Equivalent JSON:
   *
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
  public JsonQueryRequest buildHybridRequest(
      String query,
      String vector,
      int topK,
      @Nullable String filterExpression,
      @Nullable String fieldsCsv,
      @Nullable Double minScore) {
    int fetchSize = topK * OVER_FETCH_MULTIPLIER;

    Map<String, Object> queries = new LinkedHashMap<>();
    queries.put(KEYWORD_LEG, keywordLeg(query));
    queries.put(VECTOR_LEG, vectorLeg(vector, fetchSize, minScore));

    JsonQueryRequest request =
        new JsonQueryRequest()
            .setQueries(queries)
            .setLimit(topK)
            .withParam(COMBINER, true)
            .withParam(COMBINER_QUERY, List.of(KEYWORD_LEG, VECTOR_LEG))
            .withParam(COMBINER_ALGORITHM, COMBINER_RRF)
            // Per-leg candidate depth is max(start + rows, shards.rows)
            .withParam(ShardParams.SHARDS_ROWS, fetchSize);
    request.setPath(HYBRID_HANDLER);

    if (hasText(filterExpression)) {
      request.withFilter(filterExpression);
    }
    if (hasText(fieldsCsv)) {
      request.returnFields(
          Arrays.stream(fieldsCsv.split(",")).map(String::trim).filter(f -> !f.isEmpty()).toList());
    }
    return request;
  }

  private static Map<String, Object> keywordLeg(String query) {
    return Map.of(
        QUERY_TYPE_EDISMAX, Map.of("query", query, PARAM_QUERY_FIELDS, FIELD_TEXT_CATCHALL));
  }

  /**
   * kNN leg by default; with a {@code minScore}, a similarity-threshold leg so only sufficiently
   * similar documents enter fusion.
   */
  private static Map<String, Object> vectorLeg(
      String vector, int fetchSize, @Nullable Double minScore) {
    if (minScore == null) {
      // Same as shards.rows: kNN should find as many candidates as the combiner will consider
      // from this leg.
      return Map.of("knn", Map.of("f", FIELD_VECTOR, "topK", fetchSize, "query", vector));
    }
    return Map.of(
        "vectorSimilarity", Map.of("f", FIELD_VECTOR, "minReturn", minScore, "query", vector));
  }

  /**
   * Executes keyword-only search using edismax query parser.
   *
   * @param collection the Solr collection
   * @param query the text query
   * @param rows number of results to return
   * @param filterExpression optional filter query
   * @param fieldsCsv optional comma-separated list of fields to return
   * @return list of documents with scores
   */
  List<Map<String, Object>> executeKeywordSearch(
      String collection,
      String query,
      int rows,
      @Nullable String filterExpression,
      @Nullable String fieldsCsv)
      throws SolrServerException, IOException {
    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set(PARAM_QUERY, query);
    params.set(PARAM_DEF_TYPE, QUERY_TYPE_EDISMAX);
    params.set(PARAM_QUERY_FIELDS, FIELD_TEXT_CATCHALL);
    return queryWithPost(collection, params, rows, filterExpression, fieldsCsv);
  }

  /**
   * Executes vector-only search using KNN query.
   *
   * @param collection the Solr collection
   * @param query the text query (will be embedded)
   * @param rows number of results to return
   * @param filterExpression optional filter query
   * @param fieldsCsv optional comma-separated list of fields to return
   * @return list of documents with similarity scores
   */
  List<Map<String, Object>> executeVectorSearch(
      String collection,
      String query,
      int rows,
      @Nullable String filterExpression,
      @Nullable String fieldsCsv)
      throws SolrServerException, IOException {
    return executeVectorSearch(collection, query, null, rows, filterExpression, fieldsCsv);
  }

  private List<Map<String, Object>> executeVectorSearch(
      String collection,
      String query,
      @Nullable String precomputedVector,
      int rows,
      @Nullable String filterExpression,
      @Nullable String fieldsCsv)
      throws SolrServerException, IOException {
    String vectorString =
        precomputedVector != null
            ? precomputedVector
            : embeddingService.embedAndFormatForSolr(query);

    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set(PARAM_QUERY, SolrQueryUtils.buildKnnQuery(FIELD_VECTOR, rows, vectorString));
    return queryWithPost(collection, params, rows, filterExpression, fieldsCsv);
  }

  /** POST avoids "URI too long" for 1536-dimension vectors in the query string. */
  private List<Map<String, Object>> queryWithPost(
      String collection,
      ModifiableSolrParams params,
      int rows,
      @Nullable String filterExpression,
      @Nullable String fieldsCsv)
      throws SolrServerException, IOException {
    params.set(PARAM_ROWS, rows);
    setFilterQuery(params, filterExpression);
    setFieldList(params, fieldsCsv);
    return toDocumentMaps(solrClient.query(collection, params, SolrRequest.METHOD.POST));
  }

  /**
   * Fallback strategy when the combined (Solr RRF) request fails. Tries keyword-only first, then
   * vector-only as a last resort.
   */
  private SearchResponse fallbackSearch(
      String collection,
      String query,
      @Nullable String vector,
      int topK,
      @Nullable String filterExpression,
      @Nullable String fieldsCsv,
      @Nullable Double minScore) {
    // Try keyword-only first. minScore is NOT applied here: BM25 scores are unbounded,
    // so a [0..1] threshold would silently discard perfectly good keyword matches.
    try {
      List<Map<String, Object>> keywordResults =
          executeKeywordSearch(collection, query, topK, filterExpression, fieldsCsv);

      if (!keywordResults.isEmpty()) {
        log.info("Keyword-only fallback returned {} results", keywordResults.size());
        return documentsOnly(keywordResults);
      }
    } catch (SolrServerException | IOException | SolrException e) {
      log.error("Keyword fallback failed", e);
    }

    // Try vector-only as last resort. minScore applies here — these are cosine similarities.
    try {
      List<Map<String, Object>> vectorResults =
          filterByMinScore(
              executeVectorSearch(collection, query, vector, topK, filterExpression, fieldsCsv),
              minScore);

      if (!vectorResults.isEmpty()) {
        log.info("Vector-only fallback returned {} results", vectorResults.size());
        return documentsOnly(vectorResults);
      }
    } catch (Exception e) {
      // Broad on purpose: embedding the query can fail with provider-specific exceptions.
      log.error("Vector fallback failed", e);
    }

    log.warn("All search strategies returned no results for query: {}", query);
    return documentsOnly(List.of());
  }

  /**
   * Fetches schema information for the fields a collection actually uses, resolving each against
   * the explicit fields first and then the most specific matching dynamic field. Fields that match
   * neither are skipped rather than reported as unknown. If the Schema API is unavailable, the used
   * fields are returned with an {@code unknown} type.
   */
  public List<FieldInfo> getFieldsWithSchema(String collection) {
    try {
      List<FieldInfo> fieldInfos = resolveUsedFieldsAgainstSchema(collection);
      log.debug(
          "Resolved {} used fields with schema (including dynamic fields) from collection {}",
          fieldInfos.size(),
          collection);
      return fieldInfos;
    } catch (SolrServerException | IOException | SolrException e) {
      log.error("Error fetching field schema from Solr", e);
      return getActuallyUsedFields(collection).stream()
          .filter(fieldName -> !fieldName.startsWith(INTERNAL_FIELD_PREFIX))
          .map(fieldName -> new FieldInfo(fieldName, UNKNOWN_TYPE, false, true, false, true))
          .collect(Collectors.toCollection(ArrayList::new));
    }
  }

  private List<FieldInfo> resolveUsedFieldsAgainstSchema(String collection)
      throws SolrServerException, IOException {
    Map<String, Map<String, Object>> explicitByName =
        new SchemaRequest.Fields()
            .process(solrClient, collection).getFields().stream()
                .collect(Collectors.toMap(f -> (String) f.get(SCHEMA_NAME), f -> f));
    List<Map<String, Object>> dynamicFields =
        new SchemaRequest.DynamicFields().process(solrClient, collection).getDynamicFields();

    List<FieldInfo> fieldInfos = new ArrayList<>();
    for (String fieldName : getActuallyUsedFields(collection)) {
      if (isInternalOrBlank(fieldName)) {
        continue;
      }
      Map<String, Object> definition = explicitByName.get(fieldName);
      if (definition == null) {
        definition = bestDynamicFieldMatch(fieldName, dynamicFields).orElse(null);
      }
      if (definition != null) {
        fieldInfos.add(toFieldInfo(fieldName, definition));
      }
    }
    return fieldInfos;
  }

  private static boolean isInternalOrBlank(@Nullable String fieldName) {
    return fieldName == null || fieldName.isEmpty() || fieldName.startsWith(INTERNAL_FIELD_PREFIX);
  }

  private static Optional<Map<String, Object>> bestDynamicFieldMatch(
      String fieldName, List<Map<String, Object>> dynamicFields) {
    Map<String, Object> bestMatch = null;
    int bestScore = NO_MATCH;
    for (Map<String, Object> dynamicField : dynamicFields) {
      if (!(dynamicField.get(SCHEMA_NAME) instanceof String pattern)) {
        continue;
      }
      int score = dynamicMatchScore(fieldName, pattern);
      if (score > bestScore) {
        bestScore = score;
        bestMatch = dynamicField;
      }
    }
    return Optional.ofNullable(bestMatch);
  }

  /** Solr's defaults apply to absent attributes: stored and indexed default to true. */
  private static FieldInfo toFieldInfo(String fieldName, Map<String, Object> definition) {
    String type = definition.get(SCHEMA_TYPE) instanceof String s ? s : UNKNOWN_TYPE;
    boolean multiValued = Boolean.TRUE.equals(definition.get("multiValued"));
    boolean stored = !Boolean.FALSE.equals(definition.get("stored"));
    boolean docValues = Boolean.TRUE.equals(definition.get("docValues"));
    boolean indexed = !Boolean.FALSE.equals(definition.get("indexed"));
    return new FieldInfo(fieldName, type, multiValued, stored, docValues, indexed);
  }

  /**
   * Returns the length of the pattern's fixed part if {@code fieldName} matches it (longer means a
   * more specific match), or {@link #NO_MATCH}. Solr dynamic fields support a single wildcard at
   * either the start or the end.
   */
  private static int dynamicMatchScore(String fieldName, String pattern) {
    if (pattern.equals(fieldName)) {
      return pattern.length();
    }
    if (pattern.endsWith(DYNAMIC_FIELD_WILDCARD)) {
      String prefix = pattern.substring(0, pattern.length() - 1);
      return fieldName.startsWith(prefix) ? prefix.length() : NO_MATCH;
    }
    if (pattern.startsWith(DYNAMIC_FIELD_WILDCARD)) {
      String suffix = pattern.substring(1);
      return fieldName.endsWith(suffix) ? suffix.length() : NO_MATCH;
    }
    return NO_MATCH;
  }
}
