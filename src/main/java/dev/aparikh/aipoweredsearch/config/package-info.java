/**
 * Spring configuration classes for AI, Solr, and REST client setup.
 *
 * <p>Spring Modulith module. Wires the RAG beans from {@code search}. This edge points the wrong
 * way (see the follow-up in #100); keep it the only one. {@code ModularityTests} fails if this
 * module gains a dependency that is not listed here; widening the list is an architecture change.
 */
@ApplicationModule(allowedDependencies = {"search"})
@NullMarked
package dev.aparikh.aipoweredsearch.config;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
