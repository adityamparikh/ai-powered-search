package dev.aparikh.aipoweredsearch.search.rag;

import dev.aparikh.aipoweredsearch.search.SearchRepository;
import dev.aparikh.aipoweredsearch.search.model.FieldInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Table-driven tests for {@link FilterValidator}. Field names and types follow W0 finding A7.
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

    private SearchRepository repository;
    private FilterValidator validator;

    @BeforeEach
    void setUp() {
        repository = mock(SearchRepository.class);
        when(repository.getFieldsWithSchema(COLLECTION)).thenReturn(SCHEMA);
        validator = new FilterValidator(repository, Duration.ofMinutes(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "metadata_author:\"George R.R. Martin\"",
            "metadata_author:Martin",
            "metadata_price:[* TO 8]",
            "metadata_price:[* TO 9.98]",
            "metadata_price:{5 TO 9.99]",
            "metadata_price:[-1 TO 100]",
            "metadata_year:[2008 TO *]",
            "metadata_year:2011",
            "metadata_price:9.99",
            "metadata_genre:fantasy",
            "metadata_genre:\"epic fantasy\"",
            "metadata_published:[2020-01-01T00:00:00Z TO *]",
            "metadata_published:{2020-01-01T00:00:00Z TO 2021-01-01T00:00:00.000Z]",
            "metadata_year:[2008 TO 2011]",
            "metadata_author:R.R.",
            "  metadata_year:[2008 TO *]  "
    })
    void acceptsSimpleClausesOnKnownFields(String clause) {
        assertThat(validator.validate(COLLECTION, List.of(clause))).containsExactly(clause.strip());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{!func}div(1,0)",
            "{!frange l=0}metadata_price",
            "metadata_author:\"x\" {!join from=id to=id}",
            "_query_:\"{!dismax}martin\"",
            "*:*",
            "unknown_field:x",
            "metadata_author:Mar*",
            "metadata_author:Mart?n",
            "metadata_author:\"George\" OR metadata_price:[* TO 1]",
            "metadata_author:x AND metadata_year:2011",
            "(metadata_author:x)",
            "-metadata_author:x",
            "+metadata_author:x",
            "metadata_genre:[a TO z]",
            "metadata_author:[A TO M]",
            "metadata_price:\"cheap\"",
            "metadata_price:cheap",
            "metadata_price:[cheap TO 9]",
            "metadata_price:[* TO 9",
            "metadata_author:\"unterminated",
            "metadata_author:\"has \\\" escape\"",
            // values and bounds must match the field's type, or Solr answers 400
            "metadata_published:[1 TO 5]",
            "metadata_published:2024",
            "metadata_year:[2020-01-01T00:00:00Z TO *]",
            "metadata_price:[2020-01-01T00:00:00Z TO *]",
            "metadata_year:[* TO 2011.5]",
            "metadata_year:2011.5",
            // bare operators and a leading '-' are not plain tokens
            "metadata_author:AND",
            "metadata_genre:OR",
            "metadata_genre:NOT",
            "metadata_author:-foo",
            "id:grrm-01",
            "content:dragons",
            "vector:[0.1 TO 0.2]",
            "_version_:[* TO *]",
            "metadata_unindexed:x",
            "metadata_author:",
            "metadata_author",
            ""
    })
    void rejectsAnythingElse(String clause) {
        assertThat(validator.validate(COLLECTION, List.of(clause))).isEmpty();
        assertThat(FilterValidator.rejectionReason(clause.strip(), validator.filterableFields(COLLECTION))).isNotNull();
    }

    @Test
    void keepsValidClausesDropsInvalidOnesAndDeduplicates() {
        List<String> accepted = validator.validate(COLLECTION, Arrays.asList(
                "metadata_author:\"George R.R. Martin\"", "{!func}x", null, "metadata_price:[* TO 9]",
                "metadata_author:\"George R.R. Martin\""));

        assertThat(accepted).containsExactly("metadata_author:\"George R.R. Martin\"", "metadata_price:[* TO 9]");
    }

    @Test
    void nullOrEmptyInputYieldsNoFilters() {
        assertThat(validator.validate(COLLECTION, null)).isEmpty();
        assertThat(validator.validate(COLLECTION, List.of())).isEmpty();
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

        cached.validate(COLLECTION, List.of("metadata_year:2011"));
        now.set(now.get().plus(Duration.ofMinutes(4)));
        cached.validate(COLLECTION, List.of("metadata_year:2011"));
        verify(repository, times(1)).getFieldsWithSchema(COLLECTION);

        now.set(now.get().plus(Duration.ofMinutes(2)));
        cached.validate(COLLECTION, List.of("metadata_year:2011"));
        verify(repository, times(2)).getFieldsWithSchema(COLLECTION);
    }

    @Test
    void failedIntrospectionDropsEveryFilterAndIsRetriedAfterAShortInterval() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-03T00:00:00Z"));
        FilterValidator cached = new FilterValidator(repository, Duration.ofMinutes(5), clock(now));
        when(repository.getFieldsWithSchema(COLLECTION))
                .thenThrow(new IllegalStateException("solr down"))
                .thenReturn(SCHEMA);

        assertThat(cached.validate(COLLECTION, List.of("metadata_year:2011"))).isEmpty();
        // The failure is cached briefly: a degraded Solr is not asked again on every turn.
        assertThat(cached.validate(COLLECTION, List.of("metadata_year:2011"))).isEmpty();
        verify(repository, times(1)).getFieldsWithSchema(COLLECTION);

        now.set(now.get().plusSeconds(FilterValidator.FAILURE_RETRY_SECONDS + 1));
        assertThat(cached.validate(COLLECTION, List.of("metadata_year:2011"))).containsExactly("metadata_year:2011");
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

        assertThat(cached.validate(COLLECTION, List.of("metadata_year:2011"))).containsExactly("metadata_year:2011");

        now.set(now.get().plus(Duration.ofMinutes(6)));
        assertThat(cached.validate(COLLECTION, List.of("metadata_year:2011"))).containsExactly("metadata_year:2011");
        verify(repository, times(2)).getFieldsWithSchema(COLLECTION);

        // An empty refresh is treated the same way as a failed one.
        now.set(now.get().plusSeconds(FilterValidator.FAILURE_RETRY_SECONDS + 1));
        assertThat(cached.validate(COLLECTION, List.of("metadata_year:2011"))).containsExactly("metadata_year:2011");
        verify(repository, times(3)).getFieldsWithSchema(COLLECTION);
    }
}
