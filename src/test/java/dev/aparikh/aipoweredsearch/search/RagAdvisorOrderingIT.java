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
@SpringBootTest(properties = {"spring.ai.openai.api-key=test-key", "search.rag.query-rewrite.model=" + RagAdvisorOrderingIT.REWRITE_MODEL})
@Import({PostgresTestConfiguration.class, SolrTestConfiguration.class, RagAdvisorOrderingIT.StubModels.class})
class RagAdvisorOrderingIT {

    static final String STUB_ANSWER = "Try A Game of Thrones by George R.R. Martin.";
    static final String FIRST_QUESTION = "Recommend an epic fantasy series with political intrigue.";
    static final String FOLLOW_UP = "Anything cheaper by the same author?";
    static final String STANDALONE = "Books by George R.R. Martin cheaper than A Game of Thrones";
    static final String REWRITE_MODEL = "configured-rewrite-model";
    static final List<String> REWRITE_MODELS = new CopyOnWriteArrayList<>();
    static final List<String> REWRITE_PROMPTS = new CopyOnWriteArrayList<>();
    static final List<String> RANKING_PROMPTS = new CopyOnWriteArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class StubModels {

        @Bean
        @Primary
        ChatModel stubChatModel() {
            // Answers the query rewrite with STANDALONE and everything else with STUB_ANSWER.
            return prompt -> {
                boolean rewrite = prompt.getContents().contains("standalone query");
                if (rewrite) {
                    REWRITE_PROMPTS.add(prompt.getContents());
                    if (prompt.getOptions() != null) {
                        REWRITE_MODELS.add(String.valueOf(prompt.getOptions().getModel()));
                    }
                } else if (prompt.getContents().startsWith("Rank the following documents")) {
                    RANKING_PROMPTS.add(prompt.getContents());
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage(rewrite ? STANDALONE : STUB_ANSWER))));
            };
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

        searchService.ask(new AskRequest(FIRST_QUESTION, conversationId));
        searchService.ask(new AskRequest(FOLLOW_UP, conversationId));

        Query turnTwo = retriever.queries.getLast();
        assertThat(turnTwo.history())
                .filteredOn(message -> message.getMessageType() != MessageType.SYSTEM)
                .extracting(Message::getMessageType, Message::getText)
                .containsExactly(
                        tuple(MessageType.USER, FIRST_QUESTION),
                        tuple(MessageType.ASSISTANT, STUB_ANSWER),
                        tuple(MessageType.USER, FOLLOW_UP));
        assertThat(turnTwo.context()).containsEntry(ChatMemory.CONVERSATION_ID, conversationId);
    }

    @Test
    void aFollowUpIsSearchedAsAStandaloneQuery() {
        String conversationId = "rewrite-" + UUID.randomUUID().toString().substring(0, 8);

        searchService.ask(new AskRequest(FIRST_QUESTION, conversationId));
        searchService.ask(new AskRequest(FOLLOW_UP, conversationId));

        assertThat(retriever.queries.getLast().text()).isEqualTo(STANDALONE);
    }

    @Test
    void aFirstQuestionIsSearchedAsAskedWithoutARewrite() {
        int rewritesBefore = REWRITE_PROMPTS.size();

        searchService.ask(new AskRequest(FIRST_QUESTION, "first-" + UUID.randomUUID().toString().substring(0, 8)));

        assertThat(retriever.queries.getLast().text()).isEqualTo(FIRST_QUESTION);
        assertThat(REWRITE_PROMPTS).hasSize(rewritesBefore);
    }

    @Test
    void aFollowUpIsRerankedAgainstTheStandaloneQuery() {
        // The advisor hands post-processors the original question; without the rewrite the
        // reranker would judge candidates against "Anything cheaper by the same author?".
        String conversationId = "rerank-" + UUID.randomUUID().toString().substring(0, 8);

        searchService.ask(new AskRequest(FIRST_QUESTION, conversationId));
        int rankingsBefore = RANKING_PROMPTS.size();
        searchService.ask(new AskRequest(FOLLOW_UP, conversationId));

        assertThat(RANKING_PROMPTS.subList(rankingsBefore, RANKING_PROMPTS.size()))
                .singleElement().asString().contains("Query:\n" + STANDALONE);
    }

    @Test
    void theRewriteRunsOnTheConfiguredModel() {
        String conversationId = "model-" + UUID.randomUUID().toString().substring(0, 8);
        searchService.ask(new AskRequest(FIRST_QUESTION, conversationId));
        int rewritesBefore = REWRITE_MODELS.size();
        searchService.ask(new AskRequest(FOLLOW_UP, conversationId));

        assertThat(REWRITE_MODELS.subList(rewritesBefore, REWRITE_MODELS.size())).containsExactly(REWRITE_MODEL);
    }

    @Test
    void memoryAdvisorHasHigherPrecedenceThanTheRetrievalAdvisor() {
        int memoryOrder = MessageChatMemoryAdvisor.builder(chatMemory).build().getOrder();
        int retrievalOrder = RetrievalAugmentationAdvisor.builder().documentRetriever(retriever).build().getOrder();

        assertThat(memoryOrder).isLessThan(retrievalOrder);
    }
}
