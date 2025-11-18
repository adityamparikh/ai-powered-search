# Apache Solr Enhancements

This document describes the advanced Apache Solr features that have been implemented to enhance the AI-powered search
application.

## Overview

The following enhancements have been implemented to leverage native Solr capabilities:

1. **Native RRF (Reciprocal Rank Fusion)** - Better hybrid search fusion
2. **Highlighting** - Show users why results matched their query
3. **Faceting for Semantic/Hybrid Search** - Filter results by categories
4. **Spell Checking** - Suggest corrections for misspelled queries
5. **Enhanced Field Boosting** - Better relevance with title, tags, and phrase boosting
6. **Synonym Expansion** - Automatic query expansion with domain-specific synonyms

---

## 1. Native RRF (Reciprocal Rank Fusion) ⚡

### What Changed

- **Before**: Used Solr's `rerank` query parser with `reRankWeight=2.0`
- **After**: Uses native RRF query parser (available in Solr 9.8+)

### Benefits

- **Better Fusion**: RRF uses the formula `score = sum(1 / (k + rank))` which balances keyword and vector signals more
  evenly
- **Rank-Based**: Focuses on rank positions rather than raw scores, which is more robust
- **Configurable**: The `k` parameter (default: 60) allows tuning the fusion behavior

### Implementation

```java
// Native RRF query format
String keywordQuery = String.format("{!edismax qf='_text_ content^2 title^5 tags^3'}%s", query);
String vectorQuery = SolrQueryUtils.buildKnnQuery(topK * 2, vectorString);
params.

set("q",String.format("{!rrf}(%s)(%s)", keywordQuery, vectorQuery));
```

### Location

- `SearchRepository.executeHybridRerankSearch()` (line 133-253)

---

## 2. Highlighting 💡

### What It Does

Shows users **why** results matched their query by highlighting matching text snippets from the content field.

### Configuration

- **Method**: Unified Highlighter (faster, better for large docs)
- **Snippets**: 3 per document
- **Fragment Size**: 150 characters
- **Fields**: `content`

### Implementation

```java
// Enable highlighting
params.set("hl","true");
params.

set("hl.fl","content");
params.

set("hl.snippets","3");
params.

set("hl.fragsize","150");
params.

set("hl.method","unified");
```

### Response Format

```json
{
  "documents": [...],
  "facetCounts": {...},
  "highlighting": {
    "doc1": [
      "This is a <em>highlighted</em> snippet from the content...",
      "Another <em>matching</em> snippet..."
    ]
  }
}
```

### Where Applied

- ✅ Traditional search (`SearchRepository.search()`)
- ✅ Hybrid search (`SearchRepository.executeHybridRerankSearch()`)
- ❌ Semantic search (highlighting doesn't work well with pure vector search)

---

## 3. Faceting for Semantic/Hybrid Search 📊

### What It Does

Enables filtering and aggregation for semantic and hybrid search results, previously only available for traditional
search.

### Default Facet Fields

- `category` / `metadata_category`
- `year` / `metadata_year`

### Configuration

```java
// Enable faceting
solrQuery.setFacet(true);
solrQuery.

addFacetField("category");
solrQuery.

addFacetField("metadata_category");
solrQuery.

addFacetField("year");
solrQuery.

addFacetField("metadata_year");
solrQuery.

setFacetMinCount(1);
```

### Response Format

```json
{
  "documents": [...],
  "facetCounts": {
    "category": [
      {"value": "AI", "count": 42},
      {"value": "Programming", "count": 28}
    ],
    "year": [
      {"value": "2024", "count": 15},
      {"value": "2023", "count": 8}
    ]
  }
}
```

### Where Applied

- ✅ Traditional search
- ✅ Hybrid search
- ✅ Semantic search

---

## 4. Spell Checking 📝

### What It Does

Provides "Did you mean...?" suggestions for misspelled queries using Solr's spell check component.

### Features

- **Dictionary**: Built from the index (learns from your data)
- **Collation**: Returns complete corrected query (not just individual words)
- **Automatic**: Only suggests when original query returns poor results

### Configuration

```java
params.set("spellcheck","true");
params.

set("spellcheck.q",query);
params.

set("spellcheck.collate","true");
```

### Response Format

```json
{
  "documents": [...],
  "spellCheckSuggestion": {
    "suggestion": "artificial intelligence",
    "originalQuery": "artifical inteligence"
  }
}
```

### Solr Configuration

Configured in `solrconfig.xml`:

- Uses Levenshtein distance for similarity
- Minimum accuracy: configurable
- Collation enabled for complete query suggestions

### Where Applied

- ✅ Traditional search
- ✅ Hybrid search
- ❌ Semantic search (vector search is typo-tolerant by nature)

---

## 5. Enhanced Field Boosting ⚡

### What Changed

- **Before**: `qf="_text_ content^2"`
- **After**: `qf="title^5 content^2 tags^3 category^1.5 _text_"`

### Boosting Strategy

```java
query.set("qf","title^5 content^2 tags^3 category^1.5 _text_");  // Field boosts
query.

set("pf","title^10");   // Phrase boost for title
query.

set("pf2","content^5"); // Bigram phrase boost for content
query.

set("ps","2");          // Phrase slop (allows 2 words between phrase terms)
```

### Field Boost Factors

| Field    | Boost | Rationale                              |
|----------|-------|----------------------------------------|
| title    | 5x    | Most important - document title is key |
| tags     | 3x    | Highly relevant - explicit metadata    |
| content  | 2x    | Important - main document content      |
| category | 1.5x  | Moderately important - classification  |
| _text_   | 1x    | Baseline - catch-all field             |

### Phrase Boosting

- **pf (Phrase Field)**: Boosts documents where all query terms appear as an exact phrase
- **pf2 (Bigram Phrase)**: Boosts documents where query terms appear as adjacent pairs
- **ps (Phrase Slop)**: Allows up to 2 words between phrase terms while still getting boost

### Impact

- Better ranking for documents with query terms in the title
- Improved precision for phrase queries
- More relevant results for multi-word searches

### Where Applied

- ✅ Traditional search
- ✅ Hybrid search (keyword component)
- ❌ Semantic search (uses vector similarity, not field boosting)

---

## 6. Synonym Expansion 📚

### What It Does

Automatically expands queries with synonyms to improve recall. For example, searching for "java" will also find
documents mentioning "jdk" or "openjdk".

### Configuration

Configured in `managed-schema.xml`:

```xml
<analyzer type="query">
  <tokenizer class="solr.StandardTokenizerFactory"/>
  <filter class="solr.StopFilterFactory" ignoreCase="true" words="stopwords.txt"/>
  <filter class="solr.SynonymGraphFilterFactory" synonyms="synonyms.txt" ignoreCase="true" expand="true"/>
  <filter class="solr.LowerCaseFilterFactory"/>
</analyzer>
```

### Domain-Specific Synonyms

Located in `solr-config/conf/synonyms.txt`:

#### AI & Machine Learning

- `ai, artificial intelligence, machine learning, ml`
- `llm, large language model, language model`
- `nlp, natural language processing`
- `neural network, neural net, deep learning, dl`
- `embedding, embeddings, vector, vectors`
- `rag, retrieval augmented generation`

#### Programming Languages

- `java, jdk, openjdk`
- `javascript, js, ecmascript`
- `typescript, ts`
- `python, py`

#### Frameworks

- `spring, spring boot, springboot`
- `react, reactjs`
- `angular, angularjs`
- `vue, vuejs`

#### Search Technologies

- `solr, apache solr`
- `elasticsearch, elastic, es`
- `vector search, semantic search, similarity search`

#### Databases

- `postgres, postgresql, pg`
- `mysql, mariadb`
- `mongo, mongodb`

#### Cloud Providers

- `aws, amazon web services`
- `gcp, google cloud platform`
- `azure, microsoft azure`

### Adding Custom Synonyms

Edit `solr-config/conf/synonyms.txt`:

```
# Format 1: Comma-separated list (all are equivalent)
word1, word2, word3

# Format 2: Directional mapping (word1 maps to word2)
word1 => word2
```

After editing, reload the Solr core for changes to take effect.

### Where Applied

- ✅ Traditional search
- ✅ Hybrid search (keyword component)
- ❌ Semantic search (vector embeddings already capture semantic similarity)

---

## API Response Structure

### Updated SearchResponse Model

```java
public record SearchResponse(
    List<Map<String, Object>> documents,
    Map<String, List<FacetCount>> facetCounts,
    Map<String, List<String>> highlighting,        // NEW
    SpellCheckSuggestion spellCheckSuggestion      // NEW
)
```

### Example Response

```json
{
  "documents": [
    {
      "id": "doc1",
      "title": "Introduction to Spring Boot",
      "content": "Spring Boot is a framework...",
      "category": "Programming",
      "score": 0.95
    }
  ],
  "facetCounts": {
    "category": [
      {"value": "Programming", "count": 42},
      {"value": "AI", "count": 28}
    ],
    "year": [
      {"value": "2024", "count": 15}
    ]
  },
  "highlighting": {
    "doc1": [
      "<em>Spring Boot</em> is a framework for building Java applications..."
    ]
  },
  "spellCheckSuggestion": {
    "suggestion": "spring boot tutorial",
    "originalQuery": "srpign boot tutroial"
  }
}
```

---

## Future Enhancements (Not Yet Implemented)

### 🟡 Medium Priority

#### 1. Learning to Rank (LTR)

Train machine learning models on user interaction data to optimize search ranking.

**Benefits**:

- Learn optimal weights for keyword vs. vector signals
- Extract features: BM25 score, vector similarity, recency, popularity
- Significantly outperforms static weights

**Implementation Approach**:

```java
// Define features in Solr
features:[
        {name:"keyword_score",type:"originalScore"},
        {name:"vector_similarity",type:"query",params:{q:"{!knn...}"}},
        {name:"recency",type:"field",params:{field:"publish_date"}},
        {name:"popularity",type:"field",params:{field:"view_count"}}
        ]

// Query with trained model
q={!
ltr model = myModel
reRankDocs=100}
```

#### 2. More Like This (MLT)

Find similar documents without manual embedding generation.

**Usage**:

```java
// Find documents similar to doc123
q={!
mlt qf = "content"
mintf=1mindf=1}id:doc123
```

**Use Cases**:

- "Similar products" feature
- "Related articles" recommendations
- Document clustering

#### 3. Query Elevation

Editorial boosting for business requirements.

**Configuration** (`elevate.xml`):

```xml
<query text="spring boot">
  <doc id="official-spring-guide" />
  <doc id="best-practices-2025" />
</query>
```

#### 4. Terms Component (Autocomplete)

Search-as-you-type functionality.

**Usage**:

```
/solr/collection/terms?terms.fl=content&terms.prefix=spri
// Returns: spring, springs, springboot...
```

#### 5. Result Grouping/Diversification

Prevent result monopolization by a single category.

**Configuration**:

```java
params.set("group","true");
params.

set("group.field","category");
params.

set("group.limit","3"); // Max 3 per category
```

#### 6. Function Queries for Custom Scoring

Boost recent or popular documents.

**Examples**:

```java
// Boost recent docs
params.set("bf","recip(ms(NOW,publish_date),3.16e-11,1,1)");

// Boost by popularity
params.

set("boost","log(view_count)");
```

---

## Testing the Features

### Manual Testing

#### 1. Test Highlighting

```bash
curl "http://localhost:8080/api/v1/search/books?query=spring+boot"
```

Look for `highlighting` field in response.

#### 2. Test Spell Checking

```bash
curl "http://localhost:8080/api/v1/search/books?query=srpign+bool"
```

Look for `spellCheckSuggestion` in response.

#### 3. Test Faceting in Semantic Search

```bash
curl "http://localhost:8080/api/v1/search/books/semantic?query=programming"
```

Look for `facetCounts` in response.

#### 4. Test Synonym Expansion

```bash
# Search for "java" should also find "jdk" and "openjdk"
curl "http://localhost:8080/api/v1/search/books?query=java"
```

#### 5. Test RRF Hybrid Search

```bash
curl "http://localhost:8080/api/v1/search/books/hybrid?query=artificial+intelligence"
```

Results should show balanced fusion of keyword and vector search.

### Automated Testing

Run the existing test suite:

```bash
./gradlew test
```

Note: Some tests may need updates to handle the new response fields (`highlighting`, `spellCheckSuggestion`).

---

## Performance Considerations

### Highlighting

- **Impact**: Minimal overhead with Unified Highlighter
- **Optimization**: Limit `hl.snippets` and `hl.fragsize` for faster response times

### Spell Checking

- **Impact**: Slight overhead (builds dictionary from index)
- **Optimization**: Use `spellcheck.maxResultsForSuggest` to only suggest when needed

### Faceting

- **Impact**: Low overhead for fields with `docValues=true`
- **Optimization**: Use `facet.mincount=1` to exclude zero-count facets

### RRF

- **Impact**: Slightly slower than simple rerank (runs two queries)
- **Benefit**: Significantly better result quality justifies the cost

### Synonym Expansion

- **Impact**: Minimal (handled at query analysis time)
- **Note**: Only applied at query time, not at index time

---

## Configuration Files Modified

1. **`SearchRepository.java`**
    - Added RRF implementation
    - Added highlighting extraction
    - Added spell check extraction
    - Added faceting to semantic search
    - Enhanced field boosting

2. **`SearchResponse.java`**
    - Added `highlighting` field
    - Added `spellCheckSuggestion` field
    - Added backwards-compatible constructor

3. **`synonyms.txt`**
    - Added domain-specific synonyms for AI, programming, frameworks, databases, cloud

4. **`managed-schema.xml`**
    - Already configured with synonym filter (no changes needed)

5. **`solrconfig.xml`**
    - Already configured with spell check component (no changes needed)

---

## Troubleshooting

### Highlighting Not Working

- Ensure the field is `stored="true"` in the schema
- Check that `hl.fl` matches the field name
- Verify that the query actually matches the content

### Spell Check Not Suggesting

- Ensure Solr spell check component is configured in `solrconfig.xml`
- Rebuild the spell check index: `/solr/collection/spell?spellcheck.build=true`
- Check `spellcheck.accuracy` threshold (lower for more suggestions)

### Synonyms Not Expanding

- Reload the Solr core after editing `synonyms.txt`
- Verify the synonym filter is in the query analyzer (not index analyzer)
- Check `expand="true"` in the SynonymGraphFilterFactory configuration

### RRF Query Failing

- Ensure you're using Solr 9.8 or later
- Verify the vector field exists in the schema
- Check that both subqueries are valid independently

---

## References

- [Solr RRF Documentation](https://solr.apache.org/guide/solr/latest/query-guide/rrf.html)
- [Solr Highlighting](https://solr.apache.org/guide/solr/latest/query-guide/highlighting.html)
- [Solr Spell Checking](https://solr.apache.org/guide/solr/latest/query-guide/spell-checking.html)
- [Solr Faceting](https://solr.apache.org/guide/solr/latest/query-guide/faceting.html)
- [Solr Synonym Filters](https://solr.apache.org/guide/solr/latest/indexing-guide/filters.html#synonym-graph-filter)
