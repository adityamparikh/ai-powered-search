package dev.aparikh.aipoweredsearch.solr.vectorstore;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.observation.DefaultVectorStoreObservationConvention;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationDocumentation.HighCardinalityKeyNames;

/** Vector store spans are exported over OTLP, so they must not carry the search text or filter. */
class ContentFreeVectorStoreObservationConventionTest {

  private final VectorStoreObservationContext context =
      VectorStoreObservationContext.builder("solr", VectorStoreObservationContext.Operation.QUERY)
          .collectionName("books")
          .queryRequest(
              SearchRequest.builder()
                  .query("a private question")
                  .topK(7)
                  .filterExpression("author == 'Jane Doe'")
                  .build())
          .build();

  @Test
  void leavesOutTheQueryTextAndTheFilter() {
    assertThat(
            new ContentFreeVectorStoreObservationConvention().getHighCardinalityKeyValues(context))
        .extracting(KeyValue::getKey)
        .doesNotContain(
            HighCardinalityKeyNames.DB_VECTOR_QUERY_CONTENT.asString(),
            HighCardinalityKeyNames.DB_VECTOR_QUERY_FILTER.asString())
        .contains(
            HighCardinalityKeyNames.DB_COLLECTION_NAME.asString(),
            HighCardinalityKeyNames.DB_VECTOR_QUERY_TOP_K.asString());
  }

  @Test
  void theDefaultConventionWouldRecordThem() {
    assertThat(new DefaultVectorStoreObservationConvention().getHighCardinalityKeyValues(context))
        .extracting(KeyValue::getKey)
        .contains(
            HighCardinalityKeyNames.DB_VECTOR_QUERY_CONTENT.asString(),
            HighCardinalityKeyNames.DB_VECTOR_QUERY_FILTER.asString());
  }
}
