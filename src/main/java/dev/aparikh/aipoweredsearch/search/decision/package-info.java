/**
 * RAG post-processors built on Spring AI's {@link org.springframework.ai.decision.DecisionModel}
 * abstraction: they ask narrow questions about each retrieved passage and act on calibrated
 * probabilities, whichever provider answers them.
 */
@NullMarked
package dev.aparikh.aipoweredsearch.search.decision;

import org.jspecify.annotations.NullMarked;
