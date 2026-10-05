package dev.aparikh.aipoweredsearch.search.rag;

import dev.aparikh.aipoweredsearch.search.SearchRepository;
import dev.aparikh.aipoweredsearch.search.model.FieldInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Table-driven tests for {@link FilterValidator}: typed planner filters in, {@code fq} clauses out. Field names and types follow W0 finding A7.
 */
class FilterValidatorTest {

    private static final String COLLECTION = "books";

    private static final List<FieldInfo> SCHEMA = List.of(
            new FieldInfo("metadata_author", "strings", true, true, true, true),
            new FieldInfo("metadata_price", "pdouble", false, true, true, true),
            new FieldInfo("metadata_year", "pint", false, true, true, true),
            new FieldInfo("metadata_genre", "text_general", true, true, false, true),
            new FieldInfo("metadata_published", "pdate", false, true, true, true),
            new FieldInfo("id", "string", false, true, false, true),
            new FieldInfo("content", "text_general", false, true, false, true),
            new FieldInfo("vector", "knn_vector_1536", false, true, false, true),
            new FieldInfo("_version_", "plong", false, false, true, false),
            new FieldInfo("metadata_unindexed", "string", false, true, false, false));

    private static final PlannedFilter YEAR_2011 = PlannedFilter.equalTo("metadata_year", "2011");

    private SearchRepository repository;
    private FilterValidator validator;

    @BeforeEach
    void setUp() {
        repository = mock(SearchRepository.class);
        when(repository.getFieldsWithSchema(COLLECTION)).thenReturn(SCHEMA);
        validator = new FilterValidator(repository, Duration.ofMinutes(5));
    }

    private static PlannedFilter eq(String field, String value) {
        return PlannedFilter.equalTo(field, value);
    }

    private static PlannedFilter range(String field, String from, String to) {
        return PlannedFilter.range(field, from, to);
    }

    static Stream<Arguments> accepted() {
        return Stream.of(
                Arguments.of(eq("metadata_author", "George R.R. Martin"), "metadata_author:\"George R.R. Martin\""),
                Arguments.of(eq("metadata_author", "Martin"), "metadata_author:\"Martin\""),
                Arguments.of(eq("metadata_genre", "epic fantasy"), "metadata_genre:\"epic fantasy\""),
                Arguments.of(eq(" metadata_author ", "  Martin  "), "metadata_author:\"Martin\""),
                // numbers are quoted too: unquoted, "metadata_price:-1" is a parse error
                Arguments.of(eq("metadata_year", "2011"), "metadata_year:\"2011\""),
                Arguments.of(eq("metadata_price", "9.99"), "metadata_price:\"9.99\""),
                Arguments.of(eq("metadata_price", "-1"), "metadata_price:\"-1\""),
                Arguments.of(range("metadata_price", null, "9.98"), "metadata_price:[* TO 9.98]"),
                Arguments.of(range("metadata_price", "*", "8"), "metadata_price:[* TO 8]"),
                Arguments.of(range("metadata_price", "-1", "100"), "metadata_price:[-1 TO 100]"),
                Arguments.of(range("metadata_year", "2008", ""), "metadata_year:[2008 TO *]"),
                Arguments.of(range("metadata_year", "2008", "2008"), "metadata_year:[2008 TO 2008]"),
                Arguments.of(range("metadata_published", "2020-01-01T00:00:00Z", null),
                        "metadata_published:[2020-01-01T00:00:00Z TO *]"),
                // a missing operator is inferred from the values present
                Arguments.of(new PlannedFilter("metadata_year", null, null, "2008", null), "metadata_year:[2008 TO *]"),
                Arguments.of(new PlannedFilter("metadata_author", null, "Martin", null, null), "metadata_author:\"Martin\""));
    }

    @ParameterizedTest
    @MethodSource("accepted")
    void rendersValidFiltersAsClauses(PlannedFilter filter, String clause) {
        assertThat(validator.render(COLLECTION, List.of(filter))).containsExactly(clause);
    }

    static Stream<Arguments> quotedLiterally() {
        // Whatever the model writes as a value, it ends up inside quotes with \ and " escaped, so
        // Solr reads it as text: no local params, wildcards, operators or a way out of the quotes.
        return Stream.of(
                Arguments.of("{!func}div(1,0)", "metadata_author:\"{!func}div(1,0)\""),
                Arguments.of("Mar*", "metadata_author:\"Mar*\""),
                Arguments.of("x\" OR *:* OR \"", "metadata_author:\"x\\\" OR *:* OR \\\"\""),
                Arguments.of("a\\", "metadata_author:\"a\\\\\""),
                Arguments.of("AND", "metadata_author:\"AND\""),
                Arguments.of("-foo", "metadata_author:\"-foo\""));
    }

    @ParameterizedTest
    @MethodSource("quotedLiterally")
    void textValuesAreQuotedAndEscapedSoTheyStayLiteral(String value, String clause) {
        assertThat(validator.render(COLLECTION, List.of(eq("metadata_author", value)))).containsExactly(clause);
    }

    static Stream<Arguments> rejected() {
        return Stream.of(
                // fields: only known, indexed, filterable ones
                Arguments.of(eq("_query_", "{!dismax}martin")),
                Arguments.of(eq("unknown_field", "x")),
                Arguments.of(eq("*", "*")),
                Arguments.of(eq("id", "grrm-01")),
                Arguments.of(eq("content", "dragons")),
                Arguments.of(range("vector", "0.1", "0.2")),
                Arguments.of(range("_version_", null, null)),
                Arguments.of(eq("metadata_unindexed", "x")),
                Arguments.of(eq("metadata_author:x OR id", "y")),
                Arguments.of(new PlannedFilter(null, PlannedFilter.Op.EQUALS, "x", null, null)),
                // values
                Arguments.of(eq("metadata_author", "")),
                Arguments.of(eq("metadata_author", "   ")),
                Arguments.of(eq("metadata_author", "x".repeat(FilterValidator.MAX_VALUE_LENGTH + 1))),
                Arguments.of(eq("metadata_author", "line\nbreak")),
                Arguments.of(eq("metadata_price", "cheap")),
                Arguments.of(eq("metadata_year", "2011.5")),
                Arguments.of(eq("metadata_year", "2011 OR 1")),
                Arguments.of(eq("metadata_published", "2024-01-01T00:00:00Z")),
                // ranges: only on numeric or date fields, typed bounds, a real and ordered interval
                Arguments.of(range("metadata_author", "A", "M")),
                Arguments.of(range("metadata_genre", "a", "z")),
                Arguments.of(range("metadata_price", "cheap", "9")),
                Arguments.of(range("metadata_price", "1", "9]")),
                Arguments.of(range("metadata_year", null, "2011.5")),
                Arguments.of(range("metadata_year", "2020-01-01T00:00:00Z", null)),
                Arguments.of(range("metadata_published", "1", "5")),
                Arguments.of(range("metadata_price", null, null)),
                Arguments.of(range("metadata_price", "*", "*")),
                Arguments.of(range("metadata_price", "100", "10")),
                Arguments.of(range("metadata_published", "2021-01-01T00:00:00Z", "2020-01-01T00:00:00Z")),
                // instants that match the pattern but are not real dates; with both bounds present
                // these used to throw from the ordering check instead of being rejected
                Arguments.of(range("metadata_published", "2020-13-01T00:00:00Z", "2021-01-01T00:00:00Z")),
                Arguments.of(range("metadata_published", "2020-01-01T00:00:00Z", "2020-02-30T00:00:00Z")),
                Arguments.of(range("metadata_published", "2020-01-01T25:00:00Z", null)));
    }

    @ParameterizedTest
    @MethodSource("rejected")
    void dropsAnythingElse(PlannedFilter filter) {
        assertThat(validator.render(COLLECTION, List.of(filter))).isEmpty();
        assertThat(FilterValidator.render(filter, validator.filterableFields(COLLECTION)).rejection()).isNotNull();
    }

    @Test
    void keepsValidFiltersDropsInvalidOnesAndDeduplicates() {
        List<String> accepted = validator.render(COLLECTION, Arrays.asList(
                eq("metadata_author", "George R.R. Martin"), eq("unknown", "x"), null,
                range("metadata_price", null, "9"), eq("metadata_author", " George R.R. Martin ")));

        assertThat(accepted).containsExactly("metadata_author:\"George R.R. Martin\"", "metadata_price:[* TO 9]");
    }

    @Test
    void nullOrEmptyInputYieldsNoFilters() {
        assertThat(validator.render(COLLECTION, null)).isEmpty();
        assertThat(validator.render(COLLECTION, List.of())).isEmpty();
    }

    @Test
    void filterableFieldsExcludeIdContentVectorsInternalAndUnindexedFields() {
        assertThat(validator.filterableFields(COLLECTION)).containsOnlyKeys(
                "metadata_author", "metadata_genre", "metadata_price", "metadata_published", "metadata_year");
        assertThat(validator.filterableFields(COLLECTION).keySet())
                .containsExactly("metadata_author", "metadata_genre", "metadata_price", "metadata_published", "metadata_year");
    }

    private static Clock clock(AtomicReference<Instant> now) {
        return new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
    }

    @Test
    void schemaIsCachedUntilTheTtlExpires() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-03T00:00:00Z"));
        FilterValidator cached = new FilterValidator(repository, Duration.ofMinutes(5), clock(now));

        cached.render(COLLECTION, List.of(YEAR_2011));
        now.set(now.get().plus(Duration.ofMinutes(4)));
        cached.render(COLLECTION, List.of(YEAR_2011));
        verify(repository, times(1)).getFieldsWithSchema(COLLECTION);

        now.set(now.get().plus(Duration.ofMinutes(2)));
        cached.render(COLLECTION, List.of(YEAR_2011));
        verify(repository, times(2)).getFieldsWithSchema(COLLECTION);
    }

    @Test
    void failedIntrospectionDropsEveryFilterAndIsRetriedAfterAShortInterval() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-03T00:00:00Z"));
        FilterValidator cached = new FilterValidator(repository, Duration.ofMinutes(5), clock(now));
        when(repository.getFieldsWithSchema(COLLECTION))
                .thenThrow(new IllegalStateException("solr down"))
                .thenReturn(SCHEMA);

        assertThat(cached.render(COLLECTION, List.of(YEAR_2011))).isEmpty();
        // The failure is cached briefly: a degraded Solr is not asked again on every turn.
        assertThat(cached.render(COLLECTION, List.of(YEAR_2011))).isEmpty();
        verify(repository, times(1)).getFieldsWithSchema(COLLECTION);

        now.set(now.get().plusSeconds(FilterValidator.FAILURE_RETRY_SECONDS + 1));
        assertThat(cached.render(COLLECTION, List.of(YEAR_2011))).containsExactly("metadata_year:\"2011\"");
        verify(repository, times(2)).getFieldsWithSchema(COLLECTION);
    }

    @Test
    void aFailedRefreshKeepsServingThePreviousSchema() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-03T00:00:00Z"));
        FilterValidator cached = new FilterValidator(repository, Duration.ofMinutes(5), clock(now));
        when(repository.getFieldsWithSchema(COLLECTION))
                .thenReturn(SCHEMA)
                .thenThrow(new IllegalStateException("solr down"))
                .thenReturn(List.of());

        assertThat(cached.render(COLLECTION, List.of(YEAR_2011))).containsExactly("metadata_year:\"2011\"");

        now.set(now.get().plus(Duration.ofMinutes(6)));
        assertThat(cached.render(COLLECTION, List.of(YEAR_2011))).containsExactly("metadata_year:\"2011\"");
        verify(repository, times(2)).getFieldsWithSchema(COLLECTION);

        // An empty refresh is treated the same way as a failed one.
        now.set(now.get().plusSeconds(FilterValidator.FAILURE_RETRY_SECONDS + 1));
        assertThat(cached.render(COLLECTION, List.of(YEAR_2011))).containsExactly("metadata_year:\"2011\"");
        verify(repository, times(3)).getFieldsWithSchema(COLLECTION);
    }
}
