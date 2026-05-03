package io.github.ngirchev.opendaimon.it.fixture;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Deterministic embedding model for fixture tests.
 *
 * <p>Returns a unit vector of the specified dimension for all inputs.
 * All documents collapse to the same vector — use with {@code similarityThreshold=0.0}.
 * Tests pipeline plumbing, not semantic relevance.
 */
public class DeterministicEmbeddingModel implements EmbeddingModel {

    private final int dimensions;

    public DeterministicEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        var embeddings = IntStream.range(0, request.getInstructions().size())
                .mapToObj(i -> new Embedding(unitVector(), i))
                .toList();
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return unitVector();
    }

    private float[] unitVector() {
        float[] vector = new float[dimensions];
        Arrays.fill(vector, 1.0f / (float) Math.sqrt(dimensions));
        return vector;
    }
}
