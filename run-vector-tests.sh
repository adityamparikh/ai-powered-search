#!/bin/bash

# Runs the SolrVectorStore integration tests against real OpenAI embeddings.
# They are tagged "live" (@LiveModelTest) because every run makes billed OpenAI calls, so
# `./gradlew test` never runs them; this script runs them through `liveTest`, which needs
# -PallowBilledCalls=true. Ask the maintainer before running it.
set -euo pipefail

if [ -z "${OPENAI_API_KEY:-}" ]; then
    echo "OPENAI_API_KEY is not set, so the vector store tests would all be skipped."
    echo "Export a real key (a project-scoped, spend-limited one) and re-run:"
    echo "  export OPENAI_API_KEY='your-api-key-here'"
    exit 1
fi

echo "Running SolrVectorStoreIT and SolrVectorStoreObservationIT (billed OpenAI calls)..."
./gradlew liveTest -PallowBilledCalls=true \
    --tests "dev.aparikh.aipoweredsearch.solr.vectorstore.SolrVectorStoreIT" \
    --tests "dev.aparikh.aipoweredsearch.solr.vectorstore.SolrVectorStoreObservationIT" \
    --info

echo ""
echo "Test results:"
echo "============="

for f in build/test-results/liveTest/TEST-dev.aparikh.aipoweredsearch.solr.vectorstore.SolrVector*.xml; do
    if [ -f "$f" ]; then
        echo "$(basename "$f"):"
        grep "testsuite name" "$f" | sed 's/.*tests="\([0-9]*\)".*skipped="\([0-9]*\)".*failures="\([0-9]*\)".*/  Tests: \1, Skipped: \2, Failures: \3/'
    fi
done

echo ""
echo "For detailed results, check: build/reports/tests/liveTest/index.html"
