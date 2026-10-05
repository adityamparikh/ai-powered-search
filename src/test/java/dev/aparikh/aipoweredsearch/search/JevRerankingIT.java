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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code search.rag.rerank.provider=jev} against a TypeSafe API that cannot be reached: Jev reranks
 * instead of Claude, cannot score, keeps the candidates in retrieval order, and {@code /ask} still
 * answers. Needs no API keys: the chat model is a stub and the retriever returns fixed documents.
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key",
        "search.rag.rerank.provider=jev",
        "spring.ai.typesafe.api-key=test-key",
        "spring.ai.typesafe.base-url=http://127.0.0.1:9",
        "spring.ai.typesafe.timeout=1s"})
@Import({PostgresTestConfiguration.class, SolrTestConfiguration.class, JevRerankingIT.StubModels.class})
@ExtendWith(OutputCaptureExtension.class)
class JevRerankingIT {

    static final List<String> RETRIEVED = List.of("grrm-01", "grrm-02", "grrm-03");
    static final List<String> PROMPTS = new CopyOnWriteArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class StubModels {

        @Bean
        @Primary
        ChatModel stubChatModel() {
            return prompt -> {
                PROMPTS.add(prompt.getContents());
                return new ChatResponse(List.of(new Generation(new AssistantMessage("stub answer"))));
            };
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
    void jevRerankingReplacesClaudeAndFallsBackToRetrievalOrder(CapturedOutput output) {
        AskResponse response = searchService.ask(new AskRequest("A Clash of Kings", "jev-rerank"));

        assertThat(output).contains("Jev could not score 3 of 3 passages");
        assertThat(PROMPTS).noneMatch(prompt -> prompt.startsWith("Rank the following documents"));
        assertThat(response.answer()).isEqualTo("stub answer");
        assertThat(response.sources()).containsExactlyElementsOf(RETRIEVED);
    }
}
