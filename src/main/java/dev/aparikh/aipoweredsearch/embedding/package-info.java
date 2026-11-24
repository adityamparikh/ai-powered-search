/**
 * Embedding generation and vector formatting utilities.
 *
 * <p>Spring Modulith module. The bottom of the graph: it depends on no other module. {@code
 * ModularityTests} fails if this module gains a dependency that is not listed here; widening the
 * list is an architecture change.
 */
@ApplicationModule(allowedDependencies = {})
@NullMarked
package dev.aparikh.aipoweredsearch.embedding;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
