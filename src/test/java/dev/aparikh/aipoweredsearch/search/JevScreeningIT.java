package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import dev.aparikh.aipoweredsearch.search.model.AskRequest;
import dev.aparikh.aipoweredsearch.search.model.AskResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Jev passage filter switched on, against a TypeSafe API that cannot be reached: the filter
 * runs, cannot screen, and passes every candidate through, so {@code /ask} still answers with the
 * full context. Needs no API keys: the chat model is a stub and the retriever returns fixed documents.
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key",
        "search.rag.jev.enabled=true",
        "spring.ai.typesafe.api-key=test-key",
        "spring.ai.typesafe.base-url=http://127.0.0.1:9",
        "spring.ai.typesafe.timeout=1s"})
@Import({PostgresTestConfiguration.class, SolrTestConfiguration.class, JevScreeningIT.StubModels.class})
@ExtendWith(OutputCaptureExtension.class)
class JevScreeningIT {

    static final List<String> RETRIEVED = List.of("grrm-01", "inj-01", "grrm-02");

    @TestConfiguration(proxyBeanMethods = false)
    static class StubModels {

        @Bean
        @Primary
        ChatModel stubChatModel() {
            return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("stub answer"))));
        }

        @Bean
        @Primary
        HybridDocumentRetriever fixedRetriever(SearchRepository searchRepository) {
            return new HybridDocumentRetriever(searchRepository, "unused", 20) {
                @Override
                public List<Document> retrieve(Query query) {
                    return RETRIEVED.stream().map(id -> new Document(id, "Text of " + id, Map.of())).toList();
                }
            };
        }
    }

    @Autowired
    private SearchService searchService;

    @Test
    void anUnreachableTypeSafeApiPassesEveryCandidateThrough(CapturedOutput output) {
        AskResponse response = searchService.ask(new AskRequest("A Clash of Kings", "jev-unreachable"));

        assertThat(output).contains("Jev could not screen 3 of 3 passages");
        assertThat(response.answer()).isEqualTo("stub answer");
        // The stub's reply is not a ranking, so the reranker keeps the order it was given.
        assertThat(response.sources()).containsExactlyElementsOf(RETRIEVED);
    }
}
