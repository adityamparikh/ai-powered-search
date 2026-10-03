package dev.aparikh.aipoweredsearch.search.rag;

import dev.aparikh.aipoweredsearch.search.SearchRepository;
import dev.aparikh.aipoweredsearch.search.model.FieldInfo;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Admits only filter clauses that are safe and meaningful for a collection (W1, #36).
 *
 * <p>The query planner is a language model, and the question it plans may carry text from
 * indexed documents via chat history, so its {@code filters} are untrusted input headed for
 * Solr's {@code fq}. A clause survives only if it is <em>exactly one</em> of:</p>
 * <ul>
 *   <li>{@code field:value}, where the value is a single plain token (letters, digits,
 *       {@code . _ -}): no wildcards, no operators;</li>
 *   <li>{@code field:"phrase"} with no quotes or backslashes inside;</li>
 *   <li>{@code field:[low TO high]} or with {@code {}} bounds, where each bound is a number, an
 *       ISO-8601 instant or {@code *}, and only on numeric or date fields. A range on a
 *       {@code text_general} field compares tokens lexically, so {@code 10.99} would fall inside
 *       {@code [* TO 9]}.</li>
 * </ul>
 * <p>The field must be a known, filterable field of the collection: not {@code id},
 * {@code content}, a dense-vector field, or an internal {@code _field_}. That rules out local
 * params ({@code {!func}}), {@code _query_}, {@code *:*}, boolean expressions, function queries and
 * unknown fields. Rejected clauses are dropped with a DEBUG log; validation never throws.</p>
 *
 * <p>Field names and types come from {@link SearchRepository#getFieldsWithSchema(String)} (W0
 * finding A5), cached per collection for {@code ttl}. If introspection fails or finds nothing,
 * every clause is dropped: no filters is today's behaviour.</p>
 */
public class FilterValidator {

    private static final Logger log = LoggerFactory.getLogger(FilterValidator.class);

    private static final String NUMBER = "-?\\d+(?:\\.\\d+)?";
    private static final String INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?Z";
    private static final String BOUND = "(?:\\*|" + NUMBER + "|" + INSTANT + ")";

    private static final Pattern FIELD_VALUE = Pattern.compile("([A-Za-z][A-Za-z0-9_]*):([\\p{L}\\p{N}._-]+)");
    private static final Pattern FIELD_PHRASE = Pattern.compile("([A-Za-z][A-Za-z0-9_]*):\"([^\"\\\\]{1,200})\"");
    private static final Pattern FIELD_RANGE = Pattern.compile(
            "([A-Za-z][A-Za-z0-9_]*):([\\[{])\\s*(" + BOUND + ")\\s+TO\\s+(" + BOUND + ")\\s*([\\]}])");
    private static final Pattern NUMERIC_VALUE = Pattern.compile(NUMBER);

    /** Point and legacy Trie numeric/date types, single- and multi-valued. */
    private static final Set<String> RANGE_TYPES = Set.of(
            "pint", "plong", "pfloat", "pdouble", "pdate",
            "pints", "plongs", "pfloats", "pdoubles", "pdates",
            "int", "long", "float", "double", "date", "tint", "tlong", "tfloat", "tdouble", "tdate");

    /** Fields that are never sensible filter targets even though they exist in the schema. */
    private static final Set<String> EXCLUDED_FIELDS = Set.of("id", "content", "vector");

    private final SearchRepository searchRepository;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, CachedFields> cache = new ConcurrentHashMap<>();

    private record CachedFields(Map<String, String> types, Instant loadedAt) {
    }

    public FilterValidator(SearchRepository searchRepository, Duration ttl) {
        this(searchRepository, ttl, Clock.systemUTC());
    }

    FilterValidator(SearchRepository searchRepository, Duration ttl, Clock clock) {
        this.searchRepository = searchRepository;
        this.ttl = ttl;
        this.clock = clock;
    }

    /**
     * The fields a planner may filter on, with their Solr types, in a stable order.
     */
    public Map<String, String> filterableFields(String collection) {
        CachedFields cached = cache.get(collection);
        Instant now = clock.instant();
        if (cached == null || cached.loadedAt().plus(ttl).isBefore(now)) {
            Map<String, String> types = load(collection);
            if (types.isEmpty()) {
                // Don't cache a failed or empty introspection: retry on the next request.
                return types;
            }
            cached = new CachedFields(types, now);
            cache.put(collection, cached);
        }
        return cached.types();
    }

    /**
     * Returns the clauses that pass validation, trimmed and de-duplicated, in input order.
     *
     * @param collection the collection the filters will run against
     * @param clauses    candidate {@code fq} clauses, typically from the planner; may be null
     */
    public List<String> validate(String collection, @Nullable List<String> clauses) {
        if (clauses == null || clauses.isEmpty()) {
            return List.of();
        }
        Map<String, String> fields = filterableFields(collection);
        Set<String> accepted = new LinkedHashSet<>();
        for (String clause : clauses) {
            if (clause == null) {
                continue;
            }
            String trimmed = clause.strip();
            String reason = rejectionReason(trimmed, fields);
            if (reason == null) {
                accepted.add(trimmed);
            } else {
                log.debug("Dropping planner filter '{}': {}", trimmed, reason);
            }
        }
        return new ArrayList<>(accepted);
    }

    /**
     * Explains why a clause is rejected.
     *
     * @return the reason, or null if the clause is acceptable
     */
    static @Nullable String rejectionReason(String clause, Map<String, String> fields) {
        if (clause.isEmpty()) {
            return "empty";
        }
        if (clause.contains("{!") || clause.contains("_query_")) {
            return "local params or nested queries are not allowed";
        }

        Matcher range = FIELD_RANGE.matcher(clause);
        if (range.matches()) {
            String type = fields.get(range.group(1));
            if (type == null) {
                return "unknown or non-filterable field " + range.group(1);
            }
            return RANGE_TYPES.contains(type.toLowerCase(Locale.ROOT)) ? null
                    : "range on non-numeric field " + range.group(1) + " (" + type + ") would compare lexically";
        }

        Matcher phrase = FIELD_PHRASE.matcher(clause);
        if (phrase.matches()) {
            String type = fields.get(phrase.group(1));
            if (type == null) {
                return "unknown or non-filterable field " + phrase.group(1);
            }
            return RANGE_TYPES.contains(type.toLowerCase(Locale.ROOT)) ? "phrase on numeric field " + phrase.group(1) : null;
        }

        Matcher value = FIELD_VALUE.matcher(clause);
        if (value.matches()) {
            String type = fields.get(value.group(1));
            if (type == null) {
                return "unknown or non-filterable field " + value.group(1);
            }
            if (RANGE_TYPES.contains(type.toLowerCase(Locale.ROOT)) && !NUMERIC_VALUE.matcher(value.group(2)).matches()) {
                return "non-numeric value for numeric field " + value.group(1);
            }
            return null;
        }

        return "not a single field:value, field:\"phrase\" or field:[a TO b] clause";
    }

    private Map<String, String> load(String collection) {
        Map<String, String> types = new LinkedHashMap<>();
        try {
            for (FieldInfo field : searchRepository.getFieldsWithSchema(collection)) {
                String name = field.name();
                String type = field.type();
                if (name == null || type == null || name.startsWith("_") || EXCLUDED_FIELDS.contains(name)
                        || type.toLowerCase(Locale.ROOT).contains("vector") || !field.indexed()) {
                    continue;
                }
                types.put(name, type);
            }
        } catch (RuntimeException e) {
            log.warn("Field introspection failed for collection '{}'; planner filters disabled until it succeeds: {}",
                    collection, e.getMessage());
            return Map.of();
        }
        return types.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
    }
}
