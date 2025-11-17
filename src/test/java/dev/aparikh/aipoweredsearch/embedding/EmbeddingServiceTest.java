package dev.aparikh.aipoweredsearch.embedding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for EmbeddingService.
 * Tests vector generation and formatting logic with mocked embedding model.
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    private EmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        embeddingService = new EmbeddingService(embeddingModel);
    }

    @Test
    void shouldEmbedTextSuccessfully() {
        // Given
        String text = "test query";
        float[] mockEmbedding = {0.1f, 0.2f, 0.3f};

        when(embeddingModel.embed(text)).thenReturn(mockEmbedding);

        // When
        float[] result = embeddingService.embed(text);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).containsExactly(0.1f, 0.2f, 0.3f);
        verify(embeddingModel).embed(text);
    }

    @Test
    void shouldEmbedAsListSuccessfully() {
        // Given
        String text = "machine learning";
        float[] mockEmbedding = {0.5f, 0.6f, 0.7f};

        when(embeddingModel.embed(text)).thenReturn(mockEmbedding);

        // When
        List<Float> result = embeddingService.embedAsList(text);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).containsExactly(0.5f, 0.6f, 0.7f);
        verify(embeddingModel).embed(text);
    }

    @Test
    void shouldConvertFloatArrayToList() {
        // Given
        float[] embedding = {0.1f, 0.2f, 0.3f, 0.4f, 0.5f};

        // When
        List<Float> result = embeddingService.convertToList(embedding);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).hasSize(5);
        assertThat(result).containsExactly(0.1f, 0.2f, 0.3f, 0.4f, 0.5f);
    }

    @Test
    void shouldConvertEmptyFloatArrayToEmptyList() {
        // Given
        float[] embedding = {};

        // When
        List<Float> result = embeddingService.convertToList(embedding);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    @Test
    void shouldFormatListVectorForSolr() {
        // Given
        List<Float> vector = List.of(0.1f, 0.2f, 0.3f);

        // When
        String formatted = embeddingService.formatVectorForSolr(vector);

        // Then
        assertThat(formatted).isNotNull();
        assertThat(formatted).isEqualTo("[0.1, 0.2, 0.3]");
    }

    @Test
    void shouldFormatEmptyListVectorForSolr() {
        // Given
        List<Float> emptyVector = List.of();

        // When
        String formatted = embeddingService.formatVectorForSolr(emptyVector);

        // Then
        assertThat(formatted).isNotNull();
        assertThat(formatted).isEqualTo("[]");
    }

    @Test
    void shouldFormatArrayVectorForSolr() {
        // Given
        float[] vector = {0.5f, 0.6f, 0.7f};

        // When
        String formatted = embeddingService.formatVectorForSolr(vector);

        // Then
        assertThat(formatted).isNotNull();
        assertThat(formatted).isEqualTo("[0.5, 0.6, 0.7]");
    }

    @Test
    void shouldFormatEmptyArrayVectorForSolr() {
        // Given
        float[] emptyVector = {};

        // When
        String formatted = embeddingService.formatVectorForSolr(emptyVector);

        // Then
        assertThat(formatted).isNotNull();
        assertThat(formatted).isEqualTo("[]");
    }

    @Test
    void shouldEmbedAndFormatForSolr() {
        // Given
        String text = "semantic search query";
        float[] mockEmbedding = {0.8f, 0.9f, 1.0f};

        when(embeddingModel.embed(text)).thenReturn(mockEmbedding);

        // When
        String result = embeddingService.embedAndFormatForSolr(text);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo("[0.8, 0.9, 1.0]");
        verify(embeddingModel).embed(text);
    }

    @Test
    void shouldEmbedAndFormatHandleNegativeValues() {
        // Given
        String text = "test with negatives";
        float[] mockEmbedding = {-0.5f, 0.3f, -0.2f};

        when(embeddingModel.embed(text)).thenReturn(mockEmbedding);

        // When
        String result = embeddingService.embedAndFormatForSolr(text);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo("[-0.5, 0.3, -0.2]");
    }

    @Test
    void shouldEmbedAndFormatHandleLargeVector() {
        // Given
        String text = "large vector test";
        float[] mockEmbedding = new float[1536]; // OpenAI text-embedding-3-small dimension
        for (int i = 0; i < mockEmbedding.length; i++) {
            mockEmbedding[i] = i * 0.001f;
        }

        when(embeddingModel.embed(text)).thenReturn(mockEmbedding);

        // When
        String result = embeddingService.embedAndFormatForSolr(text);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).startsWith("[0.0, ");
        assertThat(result).endsWith("]");
        assertThat(result).contains(", ");
        // Verify all 1536 values are present
        String[] values = result.substring(1, result.length() - 1).split(", ");
        assertThat(values).hasSize(1536);
    }

    @Test
    void shouldFormatVectorWithHighPrecision() {
        // Given
        float[] vector = {0.123456789f, 0.987654321f};

        // When
        String formatted = embeddingService.formatVectorForSolr(vector);

        // Then
        assertThat(formatted).isNotNull();
        assertThat(formatted).startsWith("[");
        assertThat(formatted).endsWith("]");
        assertThat(formatted).contains(", ");
        // Float precision may vary, so just verify structure
        String[] values = formatted.substring(1, formatted.length() - 1).split(", ");
        assertThat(values).hasSize(2);
    }

    @Test
    void shouldReturnEmbeddingModel() {
        // When
        EmbeddingModel result = embeddingService.getEmbeddingModel();

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isSameAs(embeddingModel);
    }

    @Test
    void shouldHandleWhitespaceInText() {
        // Given
        String text = "  text with spaces  ";
        float[] mockEmbedding = {0.1f, 0.2f};

        when(embeddingModel.embed(text)).thenReturn(mockEmbedding);

        // When
        float[] result = embeddingService.embed(text);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).containsExactly(0.1f, 0.2f);
        verify(embeddingModel).embed(text);
    }

    @Test
    void shouldHandleMultilineText() {
        // Given
        String text = "line one\nline two\nline three";
        float[] mockEmbedding = {0.3f, 0.4f, 0.5f};

        when(embeddingModel.embed(text)).thenReturn(mockEmbedding);

        // When
        float[] result = embeddingService.embed(text);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).containsExactly(0.3f, 0.4f, 0.5f);
    }

    @Test
    void shouldHandleSpecialCharactersInText() {
        // Given
        String text = "Special chars: @#$%^&*()!";
        float[] mockEmbedding = {0.7f, 0.8f};

        when(embeddingModel.embed(text)).thenReturn(mockEmbedding);

        // When
        float[] result = embeddingService.embed(text);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).containsExactly(0.7f, 0.8f);
    }

    @Test
    void shouldHandleUnicodeCharacters() {
        // Given
        String text = "Unicode: \u00E9\u00F1\u4E2D\u6587";
        float[] mockEmbedding = {0.9f, 1.0f};

        when(embeddingModel.embed(text)).thenReturn(mockEmbedding);

        // When
        float[] result = embeddingService.embed(text);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).containsExactly(0.9f, 1.0f);
    }

    @Test
    void shouldFormatVectorWithZeroValues() {
        // Given
        float[] vector = {0.0f, 0.0f, 0.0f};

        // When
        String formatted = embeddingService.formatVectorForSolr(vector);

        // Then
        assertThat(formatted).isNotNull();
        assertThat(formatted).isEqualTo("[0.0, 0.0, 0.0]");
    }

    @Test
    void shouldFormatSingleElementVector() {
        // Given
        List<Float> vector = List.of(0.5f);

        // When
        String formatted = embeddingService.formatVectorForSolr(vector);

        // Then
        assertThat(formatted).isNotNull();
        assertThat(formatted).isEqualTo("[0.5]");
    }
}
