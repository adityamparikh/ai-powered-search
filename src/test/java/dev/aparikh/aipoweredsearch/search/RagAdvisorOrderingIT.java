package dev.aparikh.aipoweredsearch.search;

import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import dev.aparikh.aipoweredsearch.search.model.AskRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * W0 finding A1, proven against the real {@code AiConfig.ragChatClient} wiring: on turn 2 of a
 * conversation, the query the retriever receives carries turn 1 in its history.
 *
 * <p>That only holds because {@link MessageChatMemoryAdvisor} (order
 * {@code HIGHEST_PRECEDENCE + 200}) runs before {@link RetrievalAugmentationAdvisor} (order 0)
 * and has already spliced the remembered messages into the prompt. Needs no API keys: the chat
 * model is a stub and the retriever records instead of querying Solr.</p>
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
@Import({PostgresTestConfiguration.class, SolrTestConfiguration.class, RagAdvisorOrderingIT.StubModels.class})
class RagAdvisorOrderingIT {

    static final String STUB_ANSWER = "Try A Game of Thrones by George R.R. Martin.";

    @TestConfiguration(proxyBeanMethods = false)
    static class StubModels {

        @Bean
        @Primary
        ChatModel stubChatModel() {
            return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(STUB_ANSWER))));
        }

        @Bean
        @Primary
        RecordingRetriever recordingRetriever(SearchRepository searchRepository) {
            return new RecordingRetriever(searchRepository);
        }
    }

    static class RecordingRetriever extends HybridDocumentRetriever {

        final List<Query> queries = new CopyOnWriteArrayList<>();

        RecordingRetriever(SearchRepository searchRepository) {
            super(searchRepository, "unused", 20);
        }

        @Override
        public List<Document> retrieve(Query query) {
            queries.add(query);
            return List.of(new Document("grrm-01", "A Game of Thrones by George R.R. Martin.", Map.of()));
        }
    }

    @Autowired
    private SearchService searchService;

    @Autowired
    private RecordingRetriever retriever;

    @Autowired
    private ChatMemory chatMemory;

    @Test
    void memoryAdvisorRunsBeforeRetrievalSoTurnTwoSeesTurnOne() {
        // SPRING_AI_CHAT_MEMORY.conversation_id is varchar(36).
        String conversationId = "ordering-" + UUID.randomUUID().toString().substring(0, 8);

        searchService.ask(new AskRequest("Recommend an epic fantasy series with political intrigue.", conversationId));
        searchService.ask(new AskRequest("Anything cheaper by the same author?", conversationId));

        Query turnTwo = retriever.queries.getLast();
        assertThat(turnTwo.text()).isEqualTo("Anything cheaper by the same author?");
        assertThat(turnTwo.history())
                .filteredOn(message -> message.getMessageType() != MessageType.SYSTEM)
                .extracting(Message::getMessageType, Message::getText)
                .containsExactly(
                        tuple(MessageType.USER, "Recommend an epic fantasy series with political intrigue."),
                        tuple(MessageType.ASSISTANT, STUB_ANSWER),
                        tuple(MessageType.USER, "Anything cheaper by the same author?"));
        assertThat(turnTwo.context()).containsEntry(ChatMemory.CONVERSATION_ID, conversationId);
    }

    @Test
    void memoryAdvisorHasHigherPrecedenceThanTheRetrievalAdvisor() {
        int memoryOrder = MessageChatMemoryAdvisor.builder(chatMemory).build().getOrder();
        int retrievalOrder = RetrievalAugmentationAdvisor.builder().documentRetriever(retriever).build().getOrder();

        assertThat(memoryOrder).isLessThan(retrievalOrder);
    }
}
