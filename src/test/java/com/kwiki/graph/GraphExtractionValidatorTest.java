package com.kwiki.graph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class GraphExtractionValidatorTest {

    @Test
    void acceptsOnlyRelationsBackedByTheCurrentChunk() {
        GraphSourceChunk source = new GraphSourceChunk(1, "PAGE", 2, 3L, 4, 5,
                "parser-v1", "chunker-v1", "chunk-0", "hash");
        GraphExtractionInput input = new GraphExtractionInput(source, "真实来源文本", "", "extract-v1",
                GraphExtractionPrompt.VERSION, "resolver-v1");
        GraphEntity a = new GraphEntity("e-a", "甲", GraphEntityType.CONCEPT, List.of());
        GraphEntity b = new GraphEntity("e-b", "乙", GraphEntityType.CONCEPT, List.of());
        GraphRelation relation = new GraphRelation("r-1", "e-a", GraphPredicate.RELATED_TO, "e-b", 0.9,
                List.of(new GraphSourceRef(source.sourceChunkId(), 0, 4)));

        assertThatCode(() -> GraphExtractionValidator.validate(input,
                new GraphExtractionResult(source, List.of(a, b), List.of(relation), "extract-v1",
                        GraphExtractionPrompt.VERSION, "resolver-v1"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> GraphExtractionValidator.validate(input,
                new GraphExtractionResult(source, List.of(a, b), List.of(new GraphRelation(
                        "r-2", "e-a", GraphPredicate.RELATED_TO, "e-b", 0.9,
                        List.of(new GraphSourceRef("other-source", 0, 2)))), "extract-v1",
                        GraphExtractionPrompt.VERSION, "resolver-v1")))
                .isInstanceOf(GraphExtractionValidationException.class);
    }

    @Test
    void rejectsUnknownRelationEndpointAndOutOfRangeCitation() {
        GraphSourceChunk source = new GraphSourceChunk(1, "PAGE", 2, 3L, 4, 5,
                "parser-v1", "chunker-v1", "chunk-0", "hash");
        GraphExtractionInput input = new GraphExtractionInput(source, "abc", "", "extract-v1",
                GraphExtractionPrompt.VERSION, "resolver-v1");
        GraphEntity entity = new GraphEntity("e-a", "甲", GraphEntityType.CONCEPT, List.of());
        assertThatThrownBy(() -> GraphExtractionValidator.validate(input,
                new GraphExtractionResult(source, List.of(entity), List.of(new GraphRelation(
                        "r-1", "e-a", GraphPredicate.USES, "e-missing", 0.5,
                        List.of(new GraphSourceRef(source.sourceChunkId(), 0, 2)))), "extract-v1",
                        GraphExtractionPrompt.VERSION, "resolver-v1")))
                .isInstanceOf(GraphExtractionValidationException.class);
    }
}
