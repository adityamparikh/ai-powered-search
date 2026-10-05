package dev.aparikh.aipoweredsearch.search.rag;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The query planner's output for one question (W1, #36).
 *
 * <p>Every field comes from a language model, so every field is nullable and nothing here is
 * trusted: {@link QueryPlanningExpander} validates the plan, and {@link FilterValidator} vets
 * each filter, before any of it reaches Solr.</p>
 *
 * @param standalone   the question rewritten as a self-contained request, using the chat history
 * @param keywordQuery distinctive terms for the BM25 leg
 * @param variants     alternative phrasings, one extra retrieval each
 * @param hydePassage  a plausible catalogue description answering the question, for the kNN leg (W2)
 * @param filters      explicit constraints only, as typed filters; {@link FilterValidator#render}
 *                     turns the valid ones into Solr {@code fq} clauses
 */
public record QueryPlan(@Nullable String standalone,
                        @Nullable String keywordQuery,
                        @Nullable List<String> variants,
                        @Nullable String hydePassage,
                        @Nullable List<@Nullable PlannedFilter> filters) {
}
