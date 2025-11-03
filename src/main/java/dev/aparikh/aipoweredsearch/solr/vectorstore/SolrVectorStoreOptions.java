package dev.aparikh.aipoweredsearch.solr.vectorstore;

/**
 * Configuration options for SolrVectorStore.
 *
 * @param idFieldName       name of the ID field in Solr (default: "id")
 * @param contentFieldName  name of the content field in Solr (default: "content")
 * @param vectorFieldName   name of the vector field in Solr (default: "vector")
 * @param metadataPrefix    prefix for metadata fields (default: "metadata_")
 * @param vectorDimension   dimension of the embedding vectors (default: 1536)
 */
public record SolrVectorStoreOptions(
        String idFieldName,
        String contentFieldName,
        String vectorFieldName,
        String metadataPrefix,
        int vectorDimension
) {

    public static final String DEFAULT_ID_FIELD = "id";
    public static final String DEFAULT_CONTENT_FIELD = "content";
    public static final String DEFAULT_VECTOR_FIELD = "vector";
    public static final String DEFAULT_METADATA_PREFIX = "metadata_";
    public static final int DEFAULT_VECTOR_DIMENSION = 1536;

    public SolrVectorStoreOptions {
        if (idFieldName == null || idFieldName.isBlank()) {
            idFieldName = DEFAULT_ID_FIELD;
        }
        if (contentFieldName == null || contentFieldName.isBlank()) {
            contentFieldName = DEFAULT_CONTENT_FIELD;
        }
        if (vectorFieldName == null || vectorFieldName.isBlank()) {
            vectorFieldName = DEFAULT_VECTOR_FIELD;
        }
        if (metadataPrefix == null) {
            metadataPrefix = DEFAULT_METADATA_PREFIX;
        }
        if (vectorDimension <= 0) {
            vectorDimension = DEFAULT_VECTOR_DIMENSION;
        }
    }

    public static SolrVectorStoreOptions defaults() {
        return new SolrVectorStoreOptions(
                DEFAULT_ID_FIELD,
                DEFAULT_CONTENT_FIELD,
                DEFAULT_VECTOR_FIELD,
                DEFAULT_METADATA_PREFIX,
                DEFAULT_VECTOR_DIMENSION
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String idFieldName = DEFAULT_ID_FIELD;
        private String contentFieldName = DEFAULT_CONTENT_FIELD;
        private String vectorFieldName = DEFAULT_VECTOR_FIELD;
        private String metadataPrefix = DEFAULT_METADATA_PREFIX;
        private int vectorDimension = DEFAULT_VECTOR_DIMENSION;

        public Builder idFieldName(String idFieldName) {
            this.idFieldName = idFieldName;
            return this;
        }

        public Builder contentFieldName(String contentFieldName) {
            this.contentFieldName = contentFieldName;
            return this;
        }

        public Builder vectorFieldName(String vectorFieldName) {
            this.vectorFieldName = vectorFieldName;
            return this;
        }

        public Builder metadataPrefix(String metadataPrefix) {
            this.metadataPrefix = metadataPrefix;
            return this;
        }

        public Builder vectorDimension(int vectorDimension) {
            this.vectorDimension = vectorDimension;
            return this;
        }

        public SolrVectorStoreOptions build() {
            return new SolrVectorStoreOptions(
                    idFieldName,
                    contentFieldName,
                    vectorFieldName,
                    metadataPrefix,
                    vectorDimension
            );
        }
    }
}
