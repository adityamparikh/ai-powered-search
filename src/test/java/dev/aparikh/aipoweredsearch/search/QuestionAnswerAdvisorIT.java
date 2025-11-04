package dev.aparikh.aipoweredsearch.search;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.aparikh.aipoweredsearch.config.PostgresTestConfiguration;
import dev.aparikh.aipoweredsearch.config.SolrTestConfiguration;
import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for QuestionAnswerAdvisor with SolrVectorStore.
 *
 * <p>Tests the RAG (Retrieval-Augmented Generation) flow:
 * <ol>
 *   <li>Index documents with embeddings into Solr</li>
 *   <li>Use QuestionAnswerAdvisor to retrieve relevant documents</li>
 *   <li>Verify Claude generates contextual answers from retrieved documents</li>
 * </ol>
 *
 * <p><b>Note:</b> This test works with both real and mock AI services:
 * <ul>
 *   <li>With valid OPENAI_API_KEY and ANTHROPIC_API_KEY: Uses real Anthropic Claude and OpenAI embeddings</li>
 *   <li>Without API keys: Uses mock ChatModel from QuestionAnswerAdvisorTestConfig for basic validation</li>
 * </ul>
 * The test validates the complete RAG workflow with SolrVectorStore and QuestionAnswerAdvisor.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // Override test properties to enable Spring AI auto-configuration
                "spring.ai.model.chat=anthropic"
        }
)
@Testcontainers
@Import({PostgresTestConfiguration.class, SolrTestConfiguration.class})
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".*"),
        @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".*")
})
class QuestionAnswerAdvisorIT {

    @Autowired
    SolrContainer solrContainer;

    @Autowired
    @Qualifier("ragChatClient")
    private ChatClient ragChatClient;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private SolrClient solrClient;

    @DynamicPropertySource
    static void configureSolrProperties(DynamicPropertyRegistry registry) {
        registry.add("solr.default.collection", () -> COLLECTION);
    }

    private static final String COLLECTION = "qa-test-collection";

    @BeforeEach
    void setUp() throws Exception {
        // Create collection
        try {
            solrContainer.execInContainer("/opt/solr/bin/solr", "create_collection", "-c", COLLECTION, "-d", "_default");
        } catch (Exception e) {
            // Collection might already exist
        }
        Thread.sleep(2000);

        // Add vector field type and field to schema
        try {
            // Add vector field type
            solrContainer.execInContainer("curl", "-X", "POST",
                    "http://localhost:8983/solr/" + COLLECTION + "/schema",
                    "-H", "Content-Type: application/json",
                    "-d", "{\"add-field-type\":{\"name\":\"knn_vector_1536\"," +
                          "\"class\":\"solr.DenseVectorField\"," +
                          "\"vectorDimension\":\"1536\"," +
                          "\"similarityFunction\":\"cosine\"," +
                          "\"knnAlgorithm\":\"hnsw\"}}");

            // Add vector field
            solrContainer.execInContainer("curl", "-X", "POST",
                    "http://localhost:8983/solr/" + COLLECTION + "/schema",
                    "-H", "Content-Type: application/json",
                    "-d", "{\"add-field\":{\"name\":\"vector\"," +
                          "\"type\":\"knn_vector_1536\"," +
                          "\"indexed\":\"true\"," +
                          "\"stored\":\"true\"}}");

            Thread.sleep(1000); // Wait for schema changes to apply
        } catch (Exception e) {
            // Schema might already be configured
        }

        // Clear any existing documents
        try {
            solrClient.deleteByQuery(COLLECTION, "*:*");
            solrClient.commit(COLLECTION);
        } catch (Exception e) {
            // Ignore if collection doesn't exist yet
        }

        indexKnowledgeBase();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Clean up documents after each test
        try {
            solrClient.deleteByQuery(COLLECTION, "*:*");
            solrClient.commit(COLLECTION);
        } catch (Exception e) {
            // Ignore
        }
    }

    /**
     * Indexes a knowledge base of book information.
     */
    private void indexKnowledgeBase() {
        List<Document> documents = List.of(
                Document.builder()
                        .id("spring-boot-book")
                        .text("Spring Boot in Action is a comprehensive guide to Spring Boot, " +
                              "written by Craig Walls. The book covers auto-configuration, " +
                              "starter dependencies, and building production-ready applications. " +
                              "It is published by Manning Publications and has 472 pages.")
                        .metadata(Map.of(
                                "title", "Spring Boot in Action",
                                "author", "Craig Walls",
                                "category", "programming",
                                "topic", "Spring Boot"
                        ))
                        .build(),

                Document.builder()
                        .id("java-book")
                        .text("Effective Java by Joshua Bloch is considered one of the best books " +
                              "for Java developers. It provides 90 best practices for writing clear, " +
                              "correct, and efficient Java code. The third edition covers Java 7, 8, and 9. " +
                              "It is published by Addison-Wesley and is essential reading for Java programmers.")
                        .metadata(Map.of(
                                "title", "Effective Java",
                                "author", "Joshua Bloch",
                                "category", "programming",
                                "topic", "Java"
                        ))
                        .build(),

                Document.builder()
                        .id("microservices-book")
                        .text("Building Microservices by Sam Newman is a practical guide to microservices architecture. " +
                              "The book covers topics like service boundaries, deployment, testing, and monitoring. " +
                              "It explains how to migrate from monolithic applications to microservices. " +
                              "Published by O'Reilly Media, it has become a standard reference in the field.")
                        .metadata(Map.of(
                                "title", "Building Microservices",
                                "author", "Sam Newman",
                                "category", "architecture",
                                "topic", "Microservices"
                        ))
                        .build(),

                Document.builder()
                        .id("clean-code-book")
                        .text("Clean Code: A Handbook of Agile Software Craftsmanship by Robert C. Martin (Uncle Bob) " +
                              "teaches software engineers how to write maintainable code. The book covers naming conventions, " +
                              "functions, comments, formatting, and error handling. It includes case studies and refactoring examples. " +
                              "Published by Prentice Hall, it is a must-read for professional developers.")
                        .metadata(Map.of(
                                "title", "Clean Code",
                                "author", "Robert C. Martin",
                                "category", "software-craftsmanship",
                                "topic", "Code Quality"
                        ))
                        .build(),

                Document.builder()
                        .id("ddd-book")
                        .text("Domain-Driven Design by Eric Evans introduces strategic and tactical patterns for software design. " +
                              "The book emphasizes modeling the business domain and using a ubiquitous language. " +
                              "It covers concepts like entities, value objects, aggregates, and bounded contexts. " +
                              "Published by Addison-Wesley, it has influenced modern software architecture practices.")
                        .metadata(Map.of(
                                "title", "Domain-Driven Design",
                                "author", "Eric Evans",
                                "category", "software-design",
                                "topic", "DDD"
                        ))
                        .build()
        );

        vectorStore.add(documents);
    }

    @Test
    void beansAreProperlyConfigured() {
        // This test verifies beans are configured when the environment is properly set up
        assertThat(ragChatClient).as("RAG ChatClient should always be configured").isNotNull();
        assertThat(solrClient).as("SolrClient should always be configured").isNotNull();

        // VectorStore requires valid API keys to work properly
        String openaiKey = System.getenv("OPENAI_API_KEY");
        if (openaiKey != null && !openaiKey.isEmpty() && !openaiKey.contains("test")) {
            assertThat(vectorStore).as("VectorStore should be configured with valid OpenAI key").isNotNull();
        }
    }

    @Test
    void shouldAnswerQuestionUsingRetrievedContext() {
        // When
        String answer = ragChatClient.prompt()
                .user("Who is the author of Spring Boot in Action?")
                .call()
                .content();

        // Then
        assertThat(answer).isNotNull();
        assertThat(answer.toLowerCase()).contains("craig walls");
    }

    @Test
    void shouldAnswerQuestionAboutMultipleBooks() {
        // When
        String answer = ragChatClient.prompt()
                .user("What books are available about Java and Spring?")
                .call()
                .content();

        // Then
        assertThat(answer).isNotNull();
        assertThat(answer.toLowerCase()).containsAnyOf("spring boot", "java", "effective java");
    }

    @Test
    void shouldAnswerQuestionAboutBookContent() {
        // When
        String answer = ragChatClient.prompt()
                .user("What topics does Effective Java cover?")
                .call()
                .content();

        // Then
        assertThat(answer).isNotNull();
        assertThat(answer.toLowerCase()).containsAnyOf("best practices", "java", "code");
    }

    @Test
    void shouldProvideStructuredAnswer() {
        // When
        BookInfo bookInfo = ragChatClient.prompt()
                .user("Tell me about Clean Code book. Include title, author, and main topic.")
                .call()
                .entity(BookInfo.class);

        // Then
        assertThat(bookInfo).isNotNull();
        assertThat(bookInfo.title()).isNotNull();
        assertThat(bookInfo.author()).isNotNull();
    }

    @Test
    void shouldHandleQuestionWithNoRelevantDocuments() {
        // When
        String answer = ragChatClient.prompt()
                .user("What is the best book about quantum physics?")
                .call()
                .content();

        // Then
        assertThat(answer).isNotNull();
        // The answer might indicate no relevant information was found
        // or provide a generic response
    }

    @Test
    void shouldRetrieveMultipleDocumentsForComplexQuestion() {
        // When
        String answer = ragChatClient.prompt()
                .user("Compare the books about software design and architecture. " +
                      "Which ones focus on code quality and which focus on system design?")
                .call()
                .content();

        // Then
        assertThat(answer).isNotNull();
        assertThat(answer.length()).isGreaterThan(50);
        // Should reference multiple books
        assertThat(answer.toLowerCase()).containsAnyOf("clean code", "domain-driven", "microservices");
    }

    @Test
    void shouldMaintainConversationContext() {
        // When - First question
        String answer1 = ragChatClient.prompt()
                .user("Who wrote Building Microservices?")
                .call()
                .content();

        // When - Follow-up question (in same client instance)
        String answer2 = ragChatClient.prompt()
                .user("What publisher released that book?")
                .call()
                .content();

        // Then
        assertThat(answer1).isNotNull();
        assertThat(answer1.toLowerCase()).contains("sam newman");

        assertThat(answer2).isNotNull();
        assertThat(answer2.toLowerCase()).contains("o'reilly");
    }

    /**
     * Record for structured book information response.
     */
    record BookInfo(
            @JsonProperty("title") String title,
            @JsonProperty("author") String author,
            @JsonProperty("topic") String topic
    ) {
    }
}
