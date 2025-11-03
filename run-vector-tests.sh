#!/bin/bash

# Script to run SolrVectorStore tests with proper environment setup
echo "Running SolrVectorStore tests..."

# Check if OPENAI_API_KEY is set
if [ -z "$OPENAI_API_KEY" ]; then
    echo "WARNING: OPENAI_API_KEY is not set. Tests will be skipped."
    echo "To run tests with real embeddings, set your API key:"
    echo "  export OPENAI_API_KEY='your-api-key-here'"
    echo ""
fi

# Clean test results
./gradlew cleanTest

echo "Running SolrVectorStoreIT..."
./gradlew test --tests "dev.aparikh.aipoweredsearch.config.SolrVectorStoreIT" --info

echo ""
echo "Running SolrVectorStoreObservationIT..."
./gradlew test --tests "dev.aparikh.aipoweredsearch.config.SolrVectorStoreObservationIT" --info

echo ""
echo "Test results:"
echo "============="

# Check results
for f in build/test-results/test/TEST-dev.aparikh.aipoweredsearch.config.SolrVector*.xml; do
    if [ -f "$f" ]; then
        echo "$(basename $f):"
        grep "testsuite name" "$f" | sed 's/.*tests="\([0-9]*\)".*failures="\([0-9]*\)".*skipped="\([0-9]*\)".*/  Tests: \1, Failures: \2, Skipped: \3/'
    fi
done

echo ""
echo "For detailed results, check: build/reports/tests/test/index.html"