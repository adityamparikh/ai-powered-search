package dev.aparikh.aipoweredsearch.search;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.aparikh.aipoweredsearch.search.model.QueryGenerationResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

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
          "{!lucene v=$q}",
          // Macro expansion: an empty-default macro splits the local-params prefix, and ${q}
          // splices in the unscreened q.
          "{${x:}!join fromIndex=hr_salaries from=id to=id}ssn:1*",
          "${q}",
          "_query_:$ q",
          "price:$5"
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
    void reportsHowManyClausesTheLimitDropped() {
      List<String> clauses = new ArrayList<>(List.of(JOIN, JOIN));
      IntStream.range(0, SolrParamGuard.MAX_FILTER_QUERIES + 1)
          .forEach(i -> clauses.add("metadata_year:" + i));
      Logger logger = (Logger) LoggerFactory.getLogger(SolrParamGuard.class);
      ListAppender<ILoggingEvent> appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
      try {
        assertThat(SolrParamGuard.filterQueries(clauses))
            .hasSize(SolrParamGuard.MAX_FILTER_QUERIES);
      } finally {
        logger.detachAppender(appender);
      }

      // Two unsafe clauses are dropped individually; only the 11th safe one hits the limit.
      assertThat(appender.list)
          .map(ILoggingEvent::getFormattedMessage)
          .contains("Dropping 1 filter query clause(s) beyond the limit of 10");
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
          "query($ q,0) desc, id desc",
          "exists(query($fq)) desc",
          "div(popularity,price) desc",
          "id desc, strdist(content,\"x\",edit) desc",
          "${s}",
          "metadata_price",
          "metadata_price up",
          "score desc,"
        })
    void dropsAnythingButPlainFieldSorts(String sort) {
      assertThat(SolrParamGuard.sort(sort)).isNull();
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "score desc",
          "metadata_price asc, id desc",
          "metadata_year DESC",
          " score desc ,id asc "
        })
    void keepsPlainFieldSorts(String sort) {
      assertThat(SolrParamGuard.sort(sort)).isEqualTo(sort);
    }

    @Test
    void dropsOverlongSorts() {
      String overlong = "a".repeat(SolrParamGuard.MAX_CLAUSE_LENGTH) + " asc";

      assertThat(SolrParamGuard.sort(overlong)).isNull();
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
