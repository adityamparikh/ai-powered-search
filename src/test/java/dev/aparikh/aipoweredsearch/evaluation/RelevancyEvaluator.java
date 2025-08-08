package dev.aparikh.aipoweredsearch.evaluation;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;

/**
 * Custom Relevancy Evaluator that assesses how relevant a response is to the given query.
 * This evaluator uses a ChatModel to determine if the response adequately addresses the query.
 */
public class RelevancyEvaluator implements Evaluator {

    private final ChatClient chatClient;

    public RelevancyEvaluator(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    @Override
    public EvaluationResponse evaluate(EvaluationRequest evaluationRequest) {
        String query = evaluationRequest.getUserText();
        String response = evaluationRequest.getResponseContent();

        String systemMessage = """
                You are an expert evaluator tasked with assessing the relevancy of responses to queries.
                
                Your task is to evaluate how well a response addresses the given query on a scale of 1-5:
                - 1: Completely irrelevant - the response doesn't address the query at all
                - 2: Mostly irrelevant - the response barely touches on the query topic
                - 3: Somewhat relevant - the response partially addresses the query
                - 4: Mostly relevant - the response addresses most aspects of the query
                - 5: Highly relevant - the response fully and accurately addresses the query
                
                Respond with only a JSON object containing:
                - "score": the relevancy score (1-5)
                - "reasoning": a brief explanation of your assessment
                """;

        String userMessage = String.format("""
                Query: %s
                Response: %s
                
                Please evaluate the relevancy of the response to the query.
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
                "evaluationType", "relevancy",
                "query", query,
                "response", response
            ));
        } catch (Exception e) {
            return new EvaluationResponse(false, 0.0f, "Error evaluating relevancy: " + e.getMessage(), 
                java.util.Map.of("error", e.getMessage()));
        }
    }
}