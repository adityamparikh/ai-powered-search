package dev.aparikh.aipoweredsearch.search.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.jspecify.annotations.Nullable;

/**
 * One hard constraint from the query planner, as data rather than Solr syntax.
 *
 * <p>The planner never writes an {@code fq} clause. It names a field, an operator and plain
 * values, and {@link FilterValidator#render} builds the clause: values are quoted and escaped, so
 * nothing the model writes is parsed as query syntax. Every component comes from a language model,
 * so every component is nullable and untrusted.</p>
 *
 * @param field the field to filter on, one of the filterable fields the planner was given
 * @param op    {@link Op#EQUALS} for an exact value, {@link Op#RANGE} for bounds
 * @param value the exact value, for {@link Op#EQUALS}
 * @param from  the lower bound, inclusive, for {@link Op#RANGE}; null or {@code *} for no lower bound
 * @param to    the upper bound, inclusive, for {@link Op#RANGE}; null or {@code *} for no upper bound
 */
public record PlannedFilter(@Nullable String field,
                            @Nullable Op op,
                            @Nullable String value,
                            @Nullable String from,
                            @Nullable String to) {

    /** How the value constrains the field. */
    public enum Op {
        EQUALS,
        RANGE;

        /**
         * Reads an operator case-insensitively. An unknown one becomes null, and
         * {@link FilterValidator} infers the operator from which values are present, so a
         * misspelt operator does not fail the whole plan's JSON.
         */
        @JsonCreator
        static @Nullable Op parse(@Nullable String name) {
            if (name == null) {
                return null;
            }
            for (Op op : values()) {
                if (op.name().equalsIgnoreCase(name.strip())) {
                    return op;
                }
            }
            return null;
        }
    }

    @JsonCreator
    public PlannedFilter {
    }

    /**
     * A filter the planner wrote as a bare string, the pre-typed format. It is kept as an empty
     * filter, which {@link FilterValidator#render} drops, so one malformed entry costs that filter
     * and not the whole plan.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static PlannedFilter fromString(String ignored) {
        return new PlannedFilter(null, null, null, null, null);
    }

    public static PlannedFilter equalTo(String field, String value) {
        return new PlannedFilter(field, Op.EQUALS, value, null, null);
    }

    public static PlannedFilter range(String field, @Nullable String from, @Nullable String to) {
        return new PlannedFilter(field, Op.RANGE, null, from, to);
    }
}
