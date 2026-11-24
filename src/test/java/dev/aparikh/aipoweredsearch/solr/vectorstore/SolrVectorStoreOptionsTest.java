package dev.aparikh.aipoweredsearch.solr.vectorstore;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SolrVectorStoreOptionsTest {

  @Test
  void defaultsMatchTheProjectSchema() {
    assertThat(SolrVectorStoreOptions.defaults())
        .isEqualTo(new SolrVectorStoreOptions("id", "content", "vector", "metadata_", 1536))
        .isEqualTo(SolrVectorStoreOptions.builder().build());
  }

  @Test
  void builderSetsEveryOption() {
    SolrVectorStoreOptions options =
        SolrVectorStoreOptions.builder()
            .idFieldName("doc_id")
            .contentFieldName("body")
            .vectorFieldName("emb")
            .metadataPrefix("m_")
            .vectorDimension(384)
            .build();

    assertThat(options).isEqualTo(new SolrVectorStoreOptions("doc_id", "body", "emb", "m_", 384));
  }

  @Test
  void blankOrInvalidValuesFallBackToTheDefaults() {
    SolrVectorStoreOptions options = new SolrVectorStoreOptions(" ", null, "", null, 0);

    assertThat(options).isEqualTo(SolrVectorStoreOptions.defaults());
  }

  @Test
  void anEmptyMetadataPrefixIsKept() {
    // Only null falls back: "" is a legitimate choice (metadata stored unprefixed).
    assertThat(new SolrVectorStoreOptions("id", "content", "vector", "", -1).metadataPrefix())
        .isEmpty();
  }
}
