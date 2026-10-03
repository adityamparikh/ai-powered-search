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
import java.util.HashMap;
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
 *       {@code . _ -}, starting with a letter or digit): no wildcards, no operators, and not a bare
 *       {@code AND}, {@code OR} or {@code NOT};</li>
 *   <li>{@code field:"phrase"} with no quotes or backslashes inside;</li>
 *   <li>{@code field:[low TO high]} or with {@code {}} bounds, where each bound is {@code *} or a
 *       value of the field's type, and only on numeric or date fields. A range on a
 *       {@code text_general} field compares tokens lexically, so {@code 10.99} would fall inside
 *       {@code [* TO 9]}.</li>
 * </ul>
 * <p>Values must match the field's type, or Solr would answer 400: integers on integer fields,
 * numbers on floating-point fields, ISO-8601 instants on date fields. A date field takes ranges
 * only; a point query on one is rejected, since an instant contains {@code :} and is not a plain
 * token.</p>
 * <p>The field must be a known, filterable field of the collection: not {@code id},
 * {@code content}, a dense-vector field, or an internal {@code _field_}. That rules out local
 * params ({@code {!func}}), {@code _query_}, {@code *:*}, boolean expressions, function queries and
 * unknown fields. Rejected clauses are dropped with a DEBUG log; validation never throws.</p>
 *
 * <p>Field names and types come from {@link SearchRepository#getFieldsWithSchema(String)} (W0
 * finding A5), cached per collection for {@code ttl}. If a refresh fails or finds nothing, the
 * previous schema is served for another {@value #FAILURE_RETRY_SECONDS}s before the next attempt.
 * With no previous schema, every clause is dropped (no filters is today's behaviour), and that
 * empty result is cached for the same interval so a degraded Solr is not asked on every turn.</p>
 */
public class FilterValidator {

    private static final Logger log = LoggerFactory.getLogger(FilterValidator.class);

    /** How long a failed or empty introspection is cached before Solr is asked again. */
    static final long FAILURE_RETRY_SECONDS = 30;

    private static final String NUMBER = "-?\\d+(?:\\.\\d+)?";
    private static final String INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?Z";
    private static final String BOUND = "(?:\\*|" + NUMBER + "|" + INSTANT + ")";

    private static final Pattern FIELD_VALUE = Pattern.compile("([A-Za-z][A-Za-z0-9_]*):([\\p{L}\\p{N}][\\p{L}\\p{N}._-]*)");
    private static final Pattern FIELD_PHRASE = Pattern.compile("([A-Za-z][A-Za-z0-9_]*):\"([^\"\\\\]{1,200})\"");
    private static final Pattern FIELD_RANGE = Pattern.compile(
            "([A-Za-z][A-Za-z0-9_]*):([\\[{])\\s*(" + BOUND + ")\\s+TO\\s+(" + BOUND + ")\\s*([\\]}])");
    private static final Pattern INTEGER_VALUE = Pattern.compile("-?\\d+");
    private static final Pattern DECIMAL_VALUE = Pattern.compile(NUMBER);
    private static final Pattern INSTANT_VALUE = Pattern.compile(INSTANT);

    /** Bare boolean operators: harmless as a single-term {@code fq}, but never a meaningful value. */
    private static final Set<String> OPERATORS = Set.of("AND", "OR", "NOT");

    /**
     * Numeric and date field types, single- and multi-valued, mapped to the values they accept.
     * Only these take ranges. Matched by type <em>name</em>: Solr 9 removed the Trie classes, but
     * older configsets still name point types {@code int}, {@code date} and so on.
     */
    private static final Map<String, Pattern> RANGE_TYPES = rangeTypes();

    /** Fields that are never sensible filter targets even though they exist in the schema. */
    private static final Set<String> EXCLUDED_FIELDS = Set.of("id", "content", "vector");

    private final SearchRepository searchRepository;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, CachedFields> cache = new ConcurrentHashMap<>();

    private record CachedFields(Map<String, String> types, Instant expiresAt) {
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
        if (cached != null && !cached.expiresAt().isBefore(now)) {
            return cached.types();
        }
        Map<String, String> types = load(collection);
        Duration retry = Duration.ofSeconds(FAILURE_RETRY_SECONDS);
        if (!types.isEmpty()) {
            cached = new CachedFields(types, now.plus(ttl));
        } else if (cached != null && !cached.types().isEmpty()) {
            // Serve the last good schema rather than switching filters off until Solr recovers.
            log.warn("Field refresh for collection '{}' found nothing; keeping the previous schema", collection);
            cached = new CachedFields(cached.types(), now.plus(retry));
        } else {
            // Cache the failure briefly, so a degraded Solr is not asked again on every turn.
            cached = new CachedFields(Map.of(), now.plus(retry));
        }
        cache.put(collection, cached);
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
        Matcher phrase = FIELD_PHRASE.matcher(clause);
        Matcher value = FIELD_VALUE.matcher(clause);
        Matcher shape = range.matches() ? range : phrase.matches() ? phrase : value.matches() ? value : null;
        if (shape == null) {
            return "not a single field:value, field:\"phrase\" or field:[a TO b] clause";
        }

        String field = shape.group(1);
        String type = fields.get(field);
        if (type == null) {
            return "unknown or non-filterable field " + field;
        }
        // Null for text and string fields: they take values and phrases, never ranges.
        Pattern typedValue = RANGE_TYPES.get(type.toLowerCase(Locale.ROOT));

        if (shape == range) {
            if (typedValue == null) {
                return "range on non-numeric field " + field + " (" + type + ") would compare lexically";
            }
            return isBound(range.group(3), typedValue) && isBound(range.group(4), typedValue) ? null
                    : "range bounds do not match the type of " + field + " (" + type + ")";
        }
        if (shape == phrase) {
            return typedValue == null ? null : "phrase on numeric or date field " + field;
        }
        if (OPERATORS.contains(value.group(2))) {
            return "bare boolean operator as the value of " + field;
        }
        return typedValue == null || typedValue.matcher(value.group(2)).matches() ? null
                : "value does not match the type of " + field + " (" + type + ")";
    }

    private static boolean isBound(String bound, Pattern typedValue) {
        return "*".equals(bound) || typedValue.matcher(bound).matches();
    }

    private static Map<String, Pattern> rangeTypes() {
        Map<String, Pattern> types = new HashMap<>();
        for (String type : List.of("pint", "plong", "pints", "plongs", "int", "long", "tint", "tlong")) {
            types.put(type, INTEGER_VALUE);
        }
        for (String type : List.of("pfloat", "pdouble", "pfloats", "pdoubles", "float", "double", "tfloat", "tdouble")) {
            types.put(type, DECIMAL_VALUE);
        }
        for (String type : List.of("pdate", "pdates", "date", "tdate")) {
            types.put(type, INSTANT_VALUE);
        }
        return Map.copyOf(types);
    }

    private Map<String, String> load(String collection) {
        Map<String, String> types = new LinkedHashMap<>();
        try {
            for (FieldInfo field : searchRepository.getFieldsWithSchema(collection)) {
                String name = field.name();
                String type = field.type();
                // Fields with docValues but indexed=false can be filtered in Solr too (slowly);
                // they are skipped on purpose, keeping planner filters on indexed fields.
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
