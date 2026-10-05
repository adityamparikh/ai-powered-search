package dev.aparikh.aipoweredsearch.search.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how the planner's JSON binds to {@link QueryPlan} through {@link BeanOutputConverter}, the
 * converter behind {@code ChatClient...entity(QueryPlan.class)}. A filter the model gets wrong must
 * cost that filter, never the whole plan.
 */
class QueryPlanBindingTest {

    private final BeanOutputConverter<QueryPlan> converter = new BeanOutputConverter<>(QueryPlan.class);

    @Test
    void typedFiltersBind() {
        QueryPlan plan = converter.convert("""
                {"standalone": "s", "variants": [],
                 "filters": [{"field": "metadata_author", "op": "EQUALS", "value": "George R.R. Martin"},
                             {"field": "metadata_price", "op": "RANGE", "to": "9.98"}]}
                """);

        assertThat(plan.filters()).containsExactly(
                PlannedFilter.equalTo("metadata_author", "George R.R. Martin"),
                PlannedFilter.range("metadata_price", null, "9.98"));
    }

    @Test
    void lenientInputBindsWithoutFailingThePlan() {
        QueryPlan plan = converter.convert("""
                {"standalone": "s", "variants": [],
                 "filters": [{"field": "metadata_price", "op": "range", "from": 5, "to": 9.98},
                             {"field": "metadata_year", "op": "BETWEEN", "value": "2011"},
                             "metadata_author:\\"George R.R. Martin\\""]}
                """);

        assertThat(plan.standalone()).isEqualTo("s");
        assertThat(plan.filters()).containsExactly(
                PlannedFilter.range("metadata_price", "5", "9.98"),
                // an unknown operator becomes null; FilterValidator infers it from the values
                new PlannedFilter("metadata_year", null, "2011", null, null),
                // a pre-typed string clause becomes an empty filter, which FilterValidator drops
                new PlannedFilter(null, null, null, null, null));
    }

    @Test
    void theSchemaSentToTheModelDescribesTypedFilters() {
        assertThat(converter.getJsonSchema())
                .contains("\"field\"")
                .contains("\"op\"")
                .contains("\"EQUALS\"")
                .contains("\"RANGE\"")
                .contains("\"from\"")
                .contains("\"to\"");
    }
}
