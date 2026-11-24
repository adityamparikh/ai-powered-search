/**
 * AI-enhanced search operations including keyword, semantic, hybrid, and RAG search.
 *
 * <p>Spring Modulith module. Search may use Solr query utilities, the vector store factory and
 * embeddings, but never {@code indexing}: the two features share code only through {@code solr} and
 * {@code embedding}. {@code ModularityTests} fails if this module gains a dependency that is not
 * listed here; widening the list is an architecture change.
 */
@ApplicationModule(allowedDependencies = {"solr", "solr :: vectorstore", "embedding"})
@NullMarked
package dev.aparikh.aipoweredsearch.search;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
