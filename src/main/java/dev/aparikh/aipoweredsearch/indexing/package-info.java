/**
 * Document indexing with automatic vector embedding generation.
 *
 * <p>Spring Modulith module. Indexing writes through the vector store factory only, and never
 * reaches into {@code search}. {@code ModularityTests} fails if this module gains a dependency that
 * is not listed here; widening the list is an architecture change.
 */
@ApplicationModule(allowedDependencies = {"solr :: vectorstore"})
@NullMarked
package dev.aparikh.aipoweredsearch.indexing;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
