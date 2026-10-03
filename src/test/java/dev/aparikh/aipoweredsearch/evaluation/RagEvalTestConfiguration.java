package dev.aparikh.aipoweredsearch.evaluation;

import dev.aparikh.aipoweredsearch.search.RerankingDocumentPostProcessor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * Test-only instrumentation for {@link RagEvaluationIT}. Production wiring is untouched; this
 * configuration only observes it.
 *
 * <ul>
 *   <li>Every {@link ChatModel} bean is wrapped in a {@link UsageRecordingChatModel} so token
 *       usage can be totalled per {@code /ask}.</li>
 *   <li>A {@code @Primary} reranker that records its input is offered to
 *       {@code AiConfig.ragChatClient}. It is built exactly as
 *       {@code AiConfig.rerankingDocumentPostProcessor} builds the real one, so the pipeline
 *       under test is unchanged; it just notes the fused candidates on the way through.</li>
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
public class RagEvalTestConfiguration {

    @Bean
    static BeanPostProcessor usageRecordingChatModelPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ChatModel chatModel && !(bean instanceof UsageRecordingChatModel)) {
                    return new UsageRecordingChatModel(chatModel);
                }
                return bean;
            }
        };
    }

    @Bean
    CandidateRecorder candidateRecorder() {
        return new CandidateRecorder();
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "search.rag.rerank.enabled", havingValue = "true", matchIfMissing = true)
    RerankingDocumentPostProcessor recordingRerankingDocumentPostProcessor(
            ChatModel chatModel,
            @Value("${search.rag.rerank.top-k:5}") int topK,
            CandidateRecorder candidateRecorder) {
        return new RerankingDocumentPostProcessor(ChatClient.builder(chatModel).build(), topK) {
            @Override
            public List<Document> process(Query query, List<Document> documents) {
                candidateRecorder.record(query, documents);
                return super.process(query, documents);
            }
        };
    }
}
