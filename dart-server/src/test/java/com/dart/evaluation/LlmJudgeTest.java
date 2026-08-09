package com.dart.evaluation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmJudgeTest {

    @Test
    void parse_validJson_returnsAllFiveDimensions() {
        Map<String, Integer> scores = LlmJudge.parse(
                "{\"relevance\":5,\"accuracy\":4,\"completeness\":3,\"clarity\":5,\"sourcing\":2}");

        assertThat(scores).containsEntry("relevance", 5)
                .containsEntry("accuracy", 4)
                .containsEntry("completeness", 3)
                .containsEntry("clarity", 5)
                .containsEntry("sourcing", 2);
    }

    @Test
    void parse_missingDimension_returnsNull() {
        assertThat(LlmJudge.parse("{\"relevance\":5,\"accuracy\":4}")).isNull();
    }

    @Test
    void parse_outOfRangeScore_returnsNull() {
        assertThat(LlmJudge.parse(
                "{\"relevance\":9,\"accuracy\":4,\"completeness\":3,\"clarity\":5,\"sourcing\":2}")).isNull();
    }

    @Test
    void parse_malformedText_returnsNull() {
        assertThat(LlmJudge.parse("无法解析的输出")).isNull();
    }

    @Test
    void fallbackScores_allThree() {
        Map<String, Integer> scores = LlmJudge.fallbackScores();
        assertThat(scores).hasSize(5);
        assertThat(scores.values()).allMatch(v -> v == 3);
    }
}
