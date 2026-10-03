package dev.aparikh.aipoweredsearch.evaluation;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The evaluation fixture and evaluation set, as checked in under {@code src/test/resources/eval/}.
 */
public final class RagEvalData {

    public static final String FIXTURE_RESOURCE = "/eval/books-fixture.json";
    public static final String EVAL_SET_RESOURCE = "/eval/rag-eval-set.json";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private RagEvalData() {
    }

    /**
     * One labelled evaluation case.
     *
     * @param id          stable case id, e.g. {@code fu-01}
     * @param category    {@code follow-up | filter | vocab-gap | injection | keyword}
     * @param turns       the user turns, in order; metrics are taken on the last one
     * @param standalone  hand-written context-free rewrite of the last turn (follow-ups only)
     * @param relevantIds fixture ids a perfect retriever would surface
     * @param notes       free-form labelling notes
     */
    public record EvalCase(String id, String category, List<String> turns, @Nullable String standalone,
                           List<String> relevantIds, @Nullable String notes) {

        public String lastTurn() {
            return turns.getLast();
        }

        public boolean isFollowUp() {
            return turns.size() > 1;
        }
    }

    public record EvalSet(String reviewStatus, int version, List<EvalCase> cases) {
    }

    /**
     * A fixture book. {@code content} is what gets embedded and copied into {@code _text_};
     * the remaining fields become {@code metadata_*} fields.
     */
    public record Book(String id, String title, String author, int year, double price, String genre, String content) {

        /** Metadata as indexed through {@code /api/v1/index}; keys gain a {@code metadata_} prefix. */
        public Map<String, Object> metadata() {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("title", title);
            metadata.put("author", author);
            metadata.put("year", year);
            metadata.put("price", price);
            metadata.put("genre", genre);
            return metadata;
        }
    }

    public record BooksFixture(String description, Map<String, String> fields, List<Book> books) {
    }

    public static BooksFixture fixture() {
        return read(FIXTURE_RESOURCE, BooksFixture.class);
    }

    public static EvalSet evalSet() {
        return read(EVAL_SET_RESOURCE, EvalSet.class);
    }

    private static <T> T read(String resource, Class<T> type) {
        try (InputStream in = Objects.requireNonNull(RagEvalData.class.getResourceAsStream(resource),
                () -> "Missing test resource " + resource)) {
            return MAPPER.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
