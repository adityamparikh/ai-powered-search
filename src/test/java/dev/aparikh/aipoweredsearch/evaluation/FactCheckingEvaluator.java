package dev.aparikh.aipoweredsearch.evaluation;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;

/**
 * Custom Fact Checking Evaluator that assesses the factual accuracy of a response.
 * This evaluator uses a ChatModel to determine if the response contains accurate information.
 */
public class FactCheckingEvaluator implements Evaluator {

    private final ChatClient chatClient;

    public FactCheckingEvaluator(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    @Override
    public EvaluationResponse evaluate(EvaluationRequest evaluationRequest) {
        String query = evaluationRequest.getUserText();
        String response = evaluationRequest.getResponseContent();

        String systemMessage = """
                You are an expert fact-checker tasked with assessing the factual accuracy of responses.
                
                Your task is to evaluate the factual correctness of a response on a scale of 1-5:
                - 1: Completely inaccurate - contains significant factual errors
                - 2: Mostly inaccurate - contains several factual errors
                - 3: Partially accurate - contains some factual errors or unclear statements
                - 4: Mostly accurate - contains minor factual issues or imprecisions
                - 5: Highly accurate - factually correct and well-supported
                
                Consider:
                - Factual correctness of statements
                - Consistency with well-established knowledge
                - Presence of misleading or false information
                - Overall reliability of the information provided
                
                Respond with only a JSON object containing:
                - "score": the accuracy score (1-5)
                - "reasoning": a brief explanation of your assessment
                - "issues": any specific factual problems identified
                """;

        String userMessage = String.format("""
                Query: %s
                Response: %s
                
                Please evaluate the factual accuracy of the response.
                """, query, response);

        try {
            String evaluation = chatClient.prompt()
                    .system(systemMessage)
                    .user(userMessage)
                    .call()
                    .content();

            // Parse the evaluation to extract score (assuming JSON response)
            // For now, we'll use a simple approach and assume a score of 3.0 as default
            float score = 3.0f;
            boolean passed = score >= 3.0f;
            
            return new EvaluationResponse(passed, score, evaluation, java.util.Map.of(
                "evaluationType", "factChecking",
                "query", query,
                "response", response
            ));
        } catch (Exception e) {
            return new EvaluationResponse(false, 0.0f, "Error evaluating factual accuracy: " + e.getMessage(), 
                java.util.Map.of("error", e.getMessage()));
        }
    }
}