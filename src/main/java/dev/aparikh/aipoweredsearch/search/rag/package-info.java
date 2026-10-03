/**
 * Modular-RAG stages for {@code /api/v1/search/ask}: the pieces plugged into Spring AI's
 * {@code RetrievalAugmentationAdvisor} around {@code HybridDocumentRetriever} (epic #32).
 *
 * <p>Stages exchange per-query data through {@code Query.context()} under the keys in
 * {@link dev.aparikh.aipoweredsearch.search.rag.RagContextKeys}, and report latency through the
 * observations named in {@link dev.aparikh.aipoweredsearch.search.rag.RagObservations}. See
 * {@code docs/rag-pipeline.md}.</p>
 */
@NullMarked
package dev.aparikh.aipoweredsearch.search.rag;

import org.jspecify.annotations.NullMarked;
