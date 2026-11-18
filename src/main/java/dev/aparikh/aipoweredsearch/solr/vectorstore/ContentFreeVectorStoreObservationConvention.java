package dev.aparikh.aipoweredsearch.solr.vectorstore;

import io.micrometer.common.KeyValues;
import org.springframework.ai.vectorstore.observation.DefaultVectorStoreObservationConvention;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;

/**
 * Spring AI's default vector store observation convention, minus the query text and the filter.
 *
 * <p>The default convention records the similarity search's query text ({@code
 * db.vector.query.content}) and filter expression ({@code db.vector.query.filter}) as
 * high-cardinality key values, which become attributes on every exported span. Those are a user's
 * search text and the values filtered on, so they are left out. Everything else (collection,
 * dimensions, topK, similarity threshold) is kept.
 */
class ContentFreeVectorStoreObservationConvention extends DefaultVectorStoreObservationConvention {

  @Override
  protected KeyValues queryContent(KeyValues keyValues, VectorStoreObservationContext context) {
    return keyValues;
  }

  @Override
  protected KeyValues metadataFilter(KeyValues keyValues, VectorStoreObservationContext context) {
    return keyValues;
  }
}
