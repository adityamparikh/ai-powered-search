package dev.aparikh.aipoweredsearch.evaluation;

import dev.aparikh.aipoweredsearch.config.AiConfig;
import dev.aparikh.aipoweredsearch.search.RerankingDocumentPostProcessor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
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
 *   <li>The {@code ragDocumentJoiner} bean is decorated to record its output, i.e. the
 *       candidates after fusion and before any screening or reranking.</li>
 *   <li>A {@code @Primary} reranker that records its input (and the question it judges against)
 *       is offered to the post-processor chain. It is built exactly as
 *       {@code AiConfig.rerankingDocumentPostProcessor} builds the real one.</li>
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
    static CandidateRecorder candidateRecorder() {
        return new CandidateRecorder();
    }

    @Bean
    static BeanPostProcessor recordingJoinerPostProcessor(CandidateRecorder candidateRecorder) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if ("ragDocumentJoiner".equals(beanName) && bean instanceof DocumentJoiner joiner) {
                    return (DocumentJoiner) documentsForQuery -> {
                        List<Document> joined = joiner.join(documentsForQuery);
                        candidateRecorder.recordJoined(documentsForQuery, joined);
                        return joined;
                    };
                }
                return bean;
            }
        };
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "search.rag.rerank.enabled", havingValue = "true", matchIfMissing = true)
    RerankingDocumentPostProcessor recordingRerankingDocumentPostProcessor(
            ChatModel chatModel,
            @Value("${search.rag.rerank.top-k:5}") int topK,
            @Value("${search.rag.rerank.model:claude-sonnet-4-5}") String model,
            CandidateRecorder candidateRecorder) {
        return new RerankingDocumentPostProcessor(AiConfig.rerankChatClient(chatModel, model), topK) {
            @Override
            public List<Document> process(Query query, List<Document> documents) {
                candidateRecorder.record(query, documents);
                return super.process(query, documents);
            }
        };
    }
}
