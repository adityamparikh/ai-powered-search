package dev.aparikh.aipoweredsearch.search;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aparikh.aipoweredsearch.search.model.QueryGenerationResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SolrParamGuardTest {

  private static final String JOIN =
      "{!join fromIndex=hr_salaries from=id to=id}salary:[200000 TO *]";

  @Nested
  class FilterQueries {

    @ParameterizedTest
    @ValueSource(
        strings = {
          JOIN,
          "{!frange l=0}sum(metadata_price,metadata_year)",
          "  {!join fromIndex=hr_salaries from=id to=id}ssn:1*",
          "id:* AND {!join fromIndex=hr_salaries from=id to=id}ssn:1*",
          "filter({!join fromIndex=hr_salaries from=id to=id}ssn:1*)",
          // Solr unescapes these inside a quoted _query_ value and runs the join.
          "_query_:\"\\{!join fromIndex=hr_salaries from=id to=id\\}ssn:1*\"",
          "x:y OR _query_:\"\\u007B!join fromIndex=hr_salaries from=id to=id}ssn:1*\"",
          "_query_:$q",
          "+_val_:\"exists(query($q))\"",
          "{ !join fromIndex=hr_salaries from=id to=id}ssn:1*",
          "{!lucene v=$q}"
        })
    void dropsClausesThatReachAnotherQueryParser(String clause) {
      assertThat(SolrParamGuard.filterQueries(List.of(clause))).isNull();
    }

    @Test
    void keepsOrdinaryFiltersAndDropsOnlyTheUnsafeOne() {
      assertThat(
              SolrParamGuard.filterQueries(
                  List.of("metadata_year:[2020 TO *]", JOIN, "category:tech", "my_query_field:x")))
          .containsExactly("metadata_year:[2020 TO *]", "category:tech", "my_query_field:x");
    }

    @Test
    void capsTheNumberOfClauses() {
      List<String> many =
          IntStream.range(0, SolrParamGuard.MAX_FILTER_QUERIES + 5)
              .mapToObj(i -> "metadata_year:" + i)
              .toList();

      assertThat(SolrParamGuard.filterQueries(many))
          .hasSize(SolrParamGuard.MAX_FILTER_QUERIES)
          .startsWith("metadata_year:0");
    }

    @Test
    void dropsOverlongClauses() {
      String overlong = "content:" + "a".repeat(SolrParamGuard.MAX_CLAUSE_LENGTH);

      assertThat(SolrParamGuard.filterQueries(List.of(overlong, "category:tech")))
          .containsExactly("category:tech");
    }

    @Test
    void skipsNullAndBlankEntries() {
      List<String> clauses = new ArrayList<>(Arrays.asList(null, " ", "category:tech"));

      assertThat(SolrParamGuard.filterQueries(clauses)).containsExactly("category:tech");
      assertThat(SolrParamGuard.filterQueries(null)).isNull();
    }
  }

  @Nested
  class Sort {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "query({!join fromIndex=hr_salaries from=id to=id v='ssn:1*'},0) desc",
          // Re-parses q with the standard parser, so a join edismax ignored runs here.
          "query($q,0) desc, id desc",
          "exists(query($fq)) desc"
        })
    void dropsSortsThatRunAQuery(String sort) {
      assertThat(SolrParamGuard.sort(sort)).isNull();
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"score desc", "metadata_price asc, id desc", "div(popularity,price) desc"})
    void keepsFieldAndFunctionSorts(String sort) {
      assertThat(SolrParamGuard.sort(sort)).isEqualTo(sort);
    }
  }

  @Nested
  class FieldList {

    @ParameterizedTest
    @ValueSource(strings = {"*", "vector", "*,vector", "v*", "*tor", "vec*r", " * , vector "})
    void fallsBackToTheDefaultWhenOnlyStarOrVectorIsAsked(String fl) {
      assertThat(SolrParamGuard.fieldList(fl)).isNull();
    }

    @Test
    void removesStarAndVectorButKeepsTheRest() {
      assertThat(SolrParamGuard.fieldList("id,*,vector,content,metadata_*,score"))
          .isEqualTo("id,content,metadata_*,score");
    }

    @Test
    void dropsAliasesFunctionsTransformersAndLocalParams() {
      assertThat(
              SolrParamGuard.fieldList(
                  "id,leak:exists(query({!join fromIndex=hr_salaries from=id to=id v='ssn:1*'})),"
                      + "[subquery],[explain],{!func}sum(1,1),title"))
          .isEqualTo("id,title");
    }

    @Test
    void acceptsSpaceSeparatedFieldsAndDeduplicates() {
      assertThat(SolrParamGuard.fieldList("id title id")).isEqualTo("id,title");
    }

    @Test
    void returnsNullForAbsentFieldList() {
      assertThat(SolrParamGuard.fieldList(null)).isNull();
      assertThat(SolrParamGuard.fieldList("  ")).isNull();
    }
  }

  @Test
  void screensEveryGeneratedParameterButQ() {
    QueryGenerationResponse generated =
        new QueryGenerationResponse(
            "spring " + JOIN,
            List.of(JOIN, "category:tech"),
            "query($q,0) desc",
            "*,vector",
            List.of("category", "{!ex=t}category"),
            "{!join fromIndex=hr_salaries from=id to=id}ssn:1*");

    QueryGenerationResponse screened = SolrParamGuard.screen(generated);

    // edismax ignores local params in q (SOLR-11501), so q is passed through.
    assertThat(screened.q()).isEqualTo(generated.q());
    assertThat(screened.fq()).containsExactly("category:tech");
    assertThat(screened.sort()).isNull();
    assertThat(screened.fl()).isNull();
    assertThat(screened.facetFields()).containsExactly("category");
    assertThat(screened.facetQuery()).isNull();
  }
}
