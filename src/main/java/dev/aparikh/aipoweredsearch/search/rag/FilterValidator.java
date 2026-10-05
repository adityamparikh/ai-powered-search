package dev.aparikh.aipoweredsearch.search.rag;

import dev.aparikh.aipoweredsearch.search.SearchRepository;
import dev.aparikh.aipoweredsearch.search.model.FieldInfo;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Turns the planner's typed filters into Solr {@code fq} clauses that are safe and meaningful for a
 * collection (W1, #36).
 *
 * <p>The query planner is a language model, and the question it plans may carry text from
 * indexed documents via chat history, so its filters are untrusted input headed for Solr's
 * {@code fq}. The planner therefore never writes query syntax: it returns {@link PlannedFilter}s,
 * a field, an operator and plain values, and this class builds each clause itself.</p>
 * <ul>
 *   <li>{@link PlannedFilter.Op#EQUALS} on a text or string field becomes
 *       {@code field:"value"}, always quoted, with {@code \} and {@code "} escaped. Inside quotes
 *       Solr's parser reads nothing else, so wildcards, operators, parentheses and local params in
 *       the value stay literal text.</li>
 *   <li>{@link PlannedFilter.Op#EQUALS} on a numeric field becomes {@code field:"value"}, and only
 *       if the value is a number of the field's type. It is quoted too: unquoted, a negative
 *       number such as {@code field:-1} is a parse error, because {@code -} cannot start a
 *       term.</li>
 *   <li>{@link PlannedFilter.Op#RANGE} becomes {@code field:[from TO to]}, only on numeric or date
 *       fields, where each bound is {@code *} or a value of the field's type. A range on a
 *       {@code text_general} field would compare tokens lexically, so {@code 10.99} would fall
 *       inside {@code [* TO 9]}.</li>
 * </ul>
 * <p>Values must match the field's type, or Solr would answer 400: integers on integer fields,
 * numbers on floating-point fields, ISO-8601 instants that are real dates on date fields. A date
 * field takes ranges only. A range with both ends open, or with its bounds reversed, constrains nothing useful and is
 * dropped.</p>
 * <p>The field must be a known, filterable field of the collection: not {@code id},
 * {@code content}, a dense-vector field, or an internal {@code _field_}. Because every clause
 * starts with such a field name, it can never start with local params ({@code {!func}}) or be
 * {@code _query_} or {@code *:*}. Rejected filters are dropped with a DEBUG log. Rendering never
 * throws: a filter whose rendering fails unexpectedly is dropped with a WARN, so a bad filter
 * costs that filter and never the planner's turn.</p>
 *
 * <p>Field names and types come from {@link SearchRepository#getFieldsWithSchema(String)} (W0
 * finding A5), cached per collection for {@code ttl}. If a refresh fails or finds nothing, the
 * previous schema is served for another {@value #FAILURE_RETRY_SECONDS}s before the next attempt.
 * With no previous schema, every filter is dropped (no filters is today's behaviour), and that
 * empty result is cached for the same interval so a degraded Solr is not asked on every turn.</p>
 */
public class FilterValidator {

    private static final Logger log = LoggerFactory.getLogger(FilterValidator.class);

    /** How long a failed or empty introspection is cached before Solr is asked again. */
    static final long FAILURE_RETRY_SECONDS = 30;

    /** Longest text value accepted; real author names and titles are far shorter. */
    static final int MAX_VALUE_LENGTH = 200;

    private static final String INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?Z";
    private static final Pattern INTEGER_VALUE = Pattern.compile("-?\\d+");
    private static final Pattern DECIMAL_VALUE = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final Pattern INSTANT_VALUE = Pattern.compile(INSTANT);

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

    /**
     * A rendered clause, or the reason a filter was rejected. Exactly one is non-null.
     */
    record Rendering(@Nullable String clause, @Nullable String rejection) {

        static Rendering of(String clause) {
            return new Rendering(clause, null);
        }

        static Rendering rejected(String reason) {
            return new Rendering(null, reason);
        }
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
     * Builds an {@code fq} clause for every filter that passes validation, de-duplicated, in
     * input order.
     *
     * @param collection the collection the filters will run against
     * @param filters    the planner's filters; may be null and may contain nulls
     */
    public List<String> render(String collection, @Nullable List<@Nullable PlannedFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }
        Map<String, String> fields = filterableFields(collection);
        Set<String> clauses = new LinkedHashSet<>();
        for (PlannedFilter filter : filters) {
            if (filter == null) {
                continue;
            }
            Rendering rendering;
            try {
                rendering = render(filter, fields);
            } catch (RuntimeException e) {
                // render(PlannedFilter, ...) rejects bad input itself; this only guards against a
                // case it misses, which must cost one filter and not the planner's turn.
                log.warn("Dropping planner filter {}: rendering failed: {}", filter, e.toString());
                continue;
            }
            if (rendering.clause() != null) {
                clauses.add(rendering.clause());
            } else {
                log.debug("Dropping planner filter {}: {}", filter, rendering.rejection());
            }
        }
        return new ArrayList<>(clauses);
    }

    /**
     * Validates one filter against the collection's fields and builds its clause.
     */
    static Rendering render(PlannedFilter filter, Map<String, String> fields) {
        String field = filter.field() == null ? null : filter.field().strip();
        if (field == null || field.isEmpty()) {
            return Rendering.rejected("no field");
        }
        String type = fields.get(field);
        if (type == null) {
            return Rendering.rejected("unknown or non-filterable field " + field);
        }
        // Null for text and string fields: they take exact values, never ranges.
        Pattern typedValue = RANGE_TYPES.get(type.toLowerCase(Locale.ROOT));
        PlannedFilter.Op op = filter.op() != null ? filter.op()
                : filter.from() != null || filter.to() != null ? PlannedFilter.Op.RANGE : PlannedFilter.Op.EQUALS;

        if (op == PlannedFilter.Op.RANGE) {
            if (typedValue == null) {
                return Rendering.rejected("range on non-numeric field " + field + " (" + type + ") would compare lexically");
            }
            String from = bound(filter.from());
            String to = bound(filter.to());
            if (!isBound(from, typedValue) || !isBound(to, typedValue)) {
                return Rendering.rejected("range bounds do not match the type of " + field + " (" + type + ")");
            }
            if ("*".equals(from) && "*".equals(to)) {
                return Rendering.rejected("range on " + field + " has no bounds");
            }
            if (!"*".equals(from) && !"*".equals(to) && compare(from, to, typedValue) > 0) {
                return Rendering.rejected("range on " + field + " has its bounds reversed");
            }
            return Rendering.of(field + ":[" + from + " TO " + to + "]");
        }

        String value = filter.value() == null ? "" : filter.value().strip();
        if (value.isEmpty()) {
            return Rendering.rejected("no value for " + field);
        }
        if (typedValue == INSTANT_VALUE) {
            return Rendering.rejected("date field " + field + " takes ranges only");
        }
        if (typedValue != null && !matchesType(value, typedValue)) {
            return Rendering.rejected("value does not match the type of " + field + " (" + type + ")");
        }
        if (value.length() > MAX_VALUE_LENGTH || value.chars().anyMatch(Character::isISOControl)) {
            return Rendering.rejected("value for " + field + " is too long or contains control characters");
        }
        return Rendering.of(field + ":\"" + escapeForQuotes(value) + "\"");
    }

    /**
     * Escapes a value for a double-quoted Solr term or phrase. Inside quotes the standard query
     * parser only interprets the backslash and the closing quote, so escaping those two is enough
     * to keep the whole value literal.
     */
    static String escapeForQuotes(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String bound(@Nullable String bound) {
        return bound == null || bound.isBlank() ? "*" : bound.strip();
    }

    /**
     * Orders two bounds of the same type: numerically, or as instants.
     */
    private static int compare(String from, String to, Pattern typedValue) {
        if (typedValue == INSTANT_VALUE) {
            return Instant.parse(from).compareTo(Instant.parse(to));
        }
        return new BigDecimal(from).compareTo(new BigDecimal(to));
    }

    private static boolean isBound(String bound, Pattern typedValue) {
        return "*".equals(bound) || matchesType(bound, typedValue);
    }

    /**
     * Whether a value is of the field's type. An instant must also be a real date: the pattern
     * alone admits {@code 2020-13-01T00:00:00Z}, which Solr rejects.
     */
    private static boolean matchesType(String value, Pattern typedValue) {
        if (!typedValue.matcher(value).matches()) {
            return false;
        }
        if (typedValue == INSTANT_VALUE) {
            try {
                Instant.parse(value);
            } catch (DateTimeParseException e) {
                return false;
            }
        }
        return true;
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
