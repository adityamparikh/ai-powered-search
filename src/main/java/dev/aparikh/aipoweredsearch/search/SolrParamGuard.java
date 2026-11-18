package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.search.model.QueryGenerationResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Screens untrusted Solr parameters before they are sent to Solr.
 *
 * <p>Two sources are untrusted: the parameters Claude generates from the user's free-text query
 * ({@code fq}, {@code sort}, {@code fl}, {@code facet.field}, {@code facet.query}), which a crafted
 * query can steer, and the {@code fields} request parameter, which becomes {@code fl} with no model
 * involved. Every search path ({@link SearchService#search}, {@link SearchService#semanticSearch},
 * {@link SearchService#hybridSearch}) passes them through this class.
 *
 * <p>An unsafe clause is dropped with a WARN rather than failing the request, so a manipulated
 * model reply degrades to a broader search instead of an error. A clause is unsafe when Solr would
 * hand it to another query parser:
 *
 * <ul>
 *   <li><strong>local params</strong> (<code>&#123;!join fromIndex=...&#125;</code>, <code>
 *       &#123;!frange&#125;</code>, ...), anywhere in the clause, not just at its start: the
 *       standard parser honours them inline too. The check runs after undoing backslash and unicode
 *       escapes, because inside a quoted {@code _query_} value Solr turns <code>&#92;&#123;!join
 *       </code> and <code>&#92;u007B!join</code> back into a live join;
 *   <li><strong>the magic fields</strong> {@code _query_} and {@code _val_}, which embed a nested
 *       query or a function query;
 *   <li><strong>parameter dereferences</strong> ({@code $q}): {@code sort=query($q,0) desc} makes
 *       Solr re-parse the user's {@code q} with the standard parser, so a join that edismax ignores
 *       as the main query still runs and its result shows up in the sort order.
 * </ul>
 *
 * <p>{@code q} itself is not screened: the keyword leg parses it with edismax, which ignores local
 * params (SOLR-11501) and, with no explicit {@code uf}, does not honour {@code _query_}. Model
 * output still reaches {@code q} unchanged.
 *
 * <p>This is the schema-free half of the defence. Restricting field names to the collection's
 * schema, and the per-key allowlist, are separate (issue #89).
 */
final class SolrParamGuard {

  /** Maximum number of filter query clauses kept; later ones are dropped. */
  static final int MAX_FILTER_QUERIES = 10;

  /** Maximum length of a single {@code fq}, {@code sort} or {@code facet.query} value. */
  static final int MAX_CLAUSE_LENGTH = 512;

  /** The dense vector field: a large, internal payload no caller needs. */
  static final String VECTOR_FIELD = "vector";

  private static final Logger log = LoggerFactory.getLogger(SolrParamGuard.class);

  private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
  private static final Pattern LOCAL_PARAMS = Pattern.compile("\\{\\s*!");
  private static final Pattern MAGIC_FIELD =
      Pattern.compile("(?<![A-Za-z0-9_])_(?:query|val)_(?![A-Za-z0-9_])");
  private static final Pattern PARAM_DEREFERENCE = Pattern.compile("\\$[A-Za-z_]");

  /** A plain field name or a glob of one: no aliases, functions, transformers or local params. */
  private static final Pattern PLAIN_FIELD = Pattern.compile("[A-Za-z0-9_.\\-*]+");

  private static final Pattern FIELD_SEPARATOR = Pattern.compile("[,\\s]+");
  private static final Pattern GLOB_WILDCARD = Pattern.compile("\\*");
  private static final int MAX_LOGGED_LENGTH = 200;

  private SolrParamGuard() {}

  /**
   * Returns a copy of a generated query whose Solr parameters are safe to send, {@code q} aside.
   */
  static QueryGenerationResponse screen(QueryGenerationResponse generated) {
    return new QueryGenerationResponse(
        generated.q(),
        filterQueries(generated.fq()),
        sort(generated.sort()),
        fieldList(generated.fl()),
        facetFields(generated.facetFields()),
        facetQuery(generated.facetQuery()));
  }

  /**
   * Keeps the safe filter query clauses, up to {@value #MAX_FILTER_QUERIES}, each at most {@value
   * #MAX_CLAUSE_LENGTH} characters.
   *
   * @return the clauses to send, or null when none survive
   */
  static @Nullable List<String> filterQueries(@Nullable List<String> filterQueries) {
    if (filterQueries == null) {
      return null;
    }
    List<String> kept = new ArrayList<>();
    for (String clause : filterQueries) {
      if (clause == null || clause.isBlank()) {
        continue;
      }
      if (kept.size() == MAX_FILTER_QUERIES) {
        log.warn(
            "Dropping {} filter query clause(s) beyond the limit of {}",
            filterQueries.size() - MAX_FILTER_QUERIES,
            MAX_FILTER_QUERIES);
        break;
      }
      if (isAcceptedClause("fq", clause)) {
        kept.add(clause);
      }
    }
    return kept.isEmpty() ? null : List.copyOf(kept);
  }

  /**
   * Returns the sort if it is safe, otherwise null (Solr then sorts by relevance).
   *
   * <p>Plain function sorts such as {@code div(popularity,price) desc} are still accepted.
   */
  static @Nullable String sort(@Nullable String sort) {
    return sort != null && !sort.isBlank() && isAcceptedClause("sort", sort) ? sort : null;
  }

  /** Returns the facet query if it is safe, otherwise null. */
  static @Nullable String facetQuery(@Nullable String facetQuery) {
    return facetQuery != null
            && !facetQuery.isBlank()
            && isAcceptedClause("facet.query", facetQuery)
        ? facetQuery
        : null;
  }

  /** Keeps the facet fields that are plain field names. */
  static @Nullable List<String> facetFields(@Nullable List<String> facetFields) {
    if (facetFields == null) {
      return null;
    }
    List<String> kept = new ArrayList<>();
    for (String field : facetFields) {
      if (field == null || field.isBlank()) {
        continue;
      }
      if (PLAIN_FIELD.matcher(field).matches() && !"*".equals(field)) {
        kept.add(field);
      } else {
        log.warn("Dropping facet.field '{}': not a plain field name", forLog(field));
      }
    }
    return kept.isEmpty() ? null : List.copyOf(kept);
  }

  /**
   * Reduces a field list ({@code fl}, or the {@code fields} request parameter) to plain field names
   * and globs, without {@code *}, {@link #VECTOR_FIELD} or a glob matching it.
   *
   * <p>Anything else is dropped: aliases ({@code leak:...}), functions ({@code
   * exists(query(...))}), transformers ({@code [subquery]}) and local params can each run a query
   * and return its result as a field value.
   *
   * @return a comma-separated field list, or null when nothing survives, meaning the endpoint's
   *     default field list
   */
  static @Nullable String fieldList(@Nullable String fieldList) {
    if (fieldList == null || fieldList.isBlank()) {
      return null;
    }
    List<String> kept = new ArrayList<>();
    for (String field : FIELD_SEPARATOR.split(fieldList.trim(), -1)) {
      if (field.isEmpty()) {
        continue;
      }
      if (!PLAIN_FIELD.matcher(field).matches()) {
        log.warn("Dropping field '{}' from the field list: not a plain field name", forLog(field));
      } else if ("*".equals(field) || matchesVectorField(field)) {
        log.warn("Dropping field '{}' from the field list", forLog(field));
      } else if (!kept.contains(field)) {
        kept.add(field);
      }
    }
    return kept.isEmpty() ? null : String.join(",", kept);
  }

  /**
   * Whether a clause is free of local params, magic fields and parameter dereferences, after Solr's
   * escapes are undone.
   */
  static boolean isSafeClause(String clause) {
    String unescaped = unescape(clause);
    return !LOCAL_PARAMS.matcher(unescaped).find()
        && !MAGIC_FIELD.matcher(unescaped).find()
        && !PARAM_DEREFERENCE.matcher(unescaped).find();
  }

  private static boolean isAcceptedClause(String param, String clause) {
    if (clause.length() > MAX_CLAUSE_LENGTH) {
      log.warn(
          "Dropping {} clause of {} characters (limit {})",
          param,
          clause.length(),
          MAX_CLAUSE_LENGTH);
      return false;
    }
    if (!isSafeClause(clause)) {
      log.warn(
          "Dropping {} clause '{}': local params, _query_/_val_ and $param are not allowed",
          param,
          forLog(clause));
      return false;
    }
    return true;
  }

  /** Decodes <code>&#92;uXXXX</code> escapes, then removes the remaining backslashes. */
  private static String unescape(String clause) {
    Matcher matcher = UNICODE_ESCAPE.matcher(clause);
    StringBuilder decoded = new StringBuilder();
    while (matcher.find()) {
      char c = (char) Integer.parseInt(matcher.group(1), 16);
      matcher.appendReplacement(decoded, Matcher.quoteReplacement(String.valueOf(c)));
    }
    matcher.appendTail(decoded);
    return decoded.toString().replace("\\", "");
  }

  private static boolean matchesVectorField(String field) {
    if (!field.contains("*")) {
      return VECTOR_FIELD.equals(field);
    }
    StringBuilder regex = new StringBuilder();
    for (String part : GLOB_WILDCARD.split(field, -1)) {
      if (!regex.isEmpty()) {
        regex.append(".*");
      }
      regex.append(Pattern.quote(part));
    }
    return VECTOR_FIELD.matches(regex.toString());
  }

  /** Shortens untrusted text and strips line breaks before it is logged. */
  private static String forLog(String value) {
    String singleLine = value.replaceAll("[\\r\\n]", " ");
    return singleLine.length() <= MAX_LOGGED_LENGTH
        ? singleLine
        : singleLine.substring(0, MAX_LOGGED_LENGTH) + "...";
  }
}
