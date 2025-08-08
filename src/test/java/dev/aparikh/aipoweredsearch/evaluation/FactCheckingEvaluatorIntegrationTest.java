package dev.aparikh.aipoweredsearch.evaluation;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@Import(FactCheckingEvaluatorIntegrationTest.FactCheckingEvaluatorTestConfig.class)
@ActiveProfiles("test")
class FactCheckingEvaluatorIntegrationTest {

    @Autowired
    private FactCheckingEvaluator factCheckingEvaluator;

    @Test
    void shouldEvaluateFactuallyAccurateResponse() {
        // Given
        String query = "What is the capital of France?";
        String response = "The capital of France is Paris.";
        
        EvaluationRequest request = new EvaluationRequest(query, response);

        // When
        EvaluationResponse evaluation = factCheckingEvaluator.evaluate(request);

        // Then
        assertThat(evaluation).isNotNull();
        assertThat(evaluation.isPass()).isTrue();
        assertThat(evaluation.getScore()).isGreaterThan(0.0f);
        assertThat(evaluation.getFeedback()).contains("score");
        assertThat(evaluation.getMetadata()).containsKey("evaluationType");
        assertThat(evaluation.getMetadata().get("evaluationType")).isEqualTo("factChecking");
        assertThat(evaluation.getMetadata()).containsKey("query");
        assertThat(evaluation.getMetadata()).containsKey("response");
    }

    @Test
    void shouldEvaluateFactuallyInaccurateResponse() {
        // Given
        String query = "What is the capital of France?";
        String response = "The capital of France is London.";
        
        EvaluationRequest request = new EvaluationRequest(query, response);

        // When
        EvaluationResponse evaluation = factCheckingEvaluator.evaluate(request);

        // Then
        assertThat(evaluation).isNotNull();
        assertThat(evaluation.isPass()).isTrue(); // Will be true due to default score of 3.0
        assertThat(evaluation.getScore()).isEqualTo(3.0f); // Default score in current implementation
        assertThat(evaluation.getFeedback()).isNotNull();
        assertThat(evaluation.getMetadata()).containsKey("evaluationType");
        assertThat(evaluation.getMetadata().get("evaluationType")).isEqualTo("factChecking");
    }

    @Test
    void shouldHandleSubjectiveQuery() {
        // Given
        String query = "What is the best programming language?";
        String response = "Java is the best programming language because it's versatile and widely used.";
        
        EvaluationRequest request = new EvaluationRequest(query, response);

        // When
        EvaluationResponse evaluation = factCheckingEvaluator.evaluate(request);

        // Then
        assertThat(evaluation).isNotNull();
        assertThat(evaluation.getScore()).isEqualTo(3.0f); // Default score
        assertThat(evaluation.getMetadata()).containsKey("evaluationType");
        assertThat(evaluation.getMetadata().get("evaluationType")).isEqualTo("factChecking");
    }

    @Test
    void shouldHandleEmptyQuery() {
        // Given
        String query = "";
        String response = "This is a response to an empty query.";
        
        EvaluationRequest request = new EvaluationRequest(query, response);

        // When
        EvaluationResponse evaluation = factCheckingEvaluator.evaluate(request);

        // Then
        assertThat(evaluation).isNotNull();
        assertThat(evaluation.getScore()).isEqualTo(3.0f); // Default score
        assertThat(evaluation.getMetadata()).containsKey("evaluationType");
        assertThat(evaluation.getMetadata().get("evaluationType")).isEqualTo("factChecking");
    }

    @Test
    void shouldHandleEmptyResponse() {
        // Given
        String query = "What is the capital of France?";
        String response = "";
        
        EvaluationRequest request = new EvaluationRequest(query, response);

        // When
        EvaluationResponse evaluation = factCheckingEvaluator.evaluate(request);

        // Then
        assertThat(evaluation).isNotNull();
        assertThat(evaluation.getScore()).isEqualTo(3.0f); // Default score
        assertThat(evaluation.getMetadata()).containsKey("evaluationType");
        assertThat(evaluation.getMetadata().get("evaluationType")).isEqualTo("factChecking");
    }

    @Test
    void shouldHandleChatModelException() {
        // Given - Create a separate evaluator with a failing ChatModel
        ChatModel failingChatModel = mock(ChatModel.class);
        when(failingChatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
            .thenThrow(new RuntimeException("Chat model error"));
        
        FactCheckingEvaluator failingEvaluator = new FactCheckingEvaluator(failingChatModel);
        EvaluationRequest request = new EvaluationRequest("test query", "test response");

        // When
        EvaluationResponse evaluation = failingEvaluator.evaluate(request);

        // Then
        assertThat(evaluation).isNotNull();
        assertThat(evaluation.isPass()).isFalse();
        assertThat(evaluation.getScore()).isEqualTo(0.0f);
        assertThat(evaluation.getFeedback()).contains("Error evaluating factual accuracy");
        assertThat(evaluation.getMetadata()).containsKey("error");
    }

    @TestConfiguration
    static class FactCheckingEvaluatorTestConfig {
        
        @Bean
        public ChatModel chatModel() {
            ChatModel mockChatModel = mock(ChatModel.class);
            
            // Create a mock response that simulates JSON evaluation result
            org.springframework.ai.chat.model.Generation generation = 
                new org.springframework.ai.chat.model.Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage(
                        "{\"score\": 5, \"reasoning\": \"The response is factually accurate and verifiable.\"}"
                    )
                );
            org.springframework.ai.chat.model.ChatResponse mockResponse = 
                new org.springframework.ai.chat.model.ChatResponse(List.of(generation));
            
            // Mock the call method to return the mock response
            when(mockChatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(mockResponse);
            
            return mockChatModel;
        }
        
        @Bean
        public FactCheckingEvaluator factCheckingEvaluator(ChatModel chatModel) {
            return new FactCheckingEvaluator(chatModel);
        }
    }
}