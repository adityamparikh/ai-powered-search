/**
 * Apache Solr query utilities.
 *
 * <p>Spring Modulith module. Shared infrastructure: it may use embeddings, but no feature module
 * and not {@code config}. {@code ModularityTests} fails if this module gains a dependency that is
 * not listed here; widening the list is an architecture change.
 */
@ApplicationModule(allowedDependencies = {"embedding"})
@NullMarked
package dev.aparikh.aipoweredsearch.solr;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
