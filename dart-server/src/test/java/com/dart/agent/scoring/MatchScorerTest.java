package com.dart.agent.scoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchScorerTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private ChatResponse chatResponse;

    private MatchScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new MatchScorer(chatModel);
    }

    private static Generation gen(String text) {
        return new Generation(new AssistantMessage(text));
    }

    @Test
    void evaluate_whenLLMReturns5_returns5() {
        when(chatResponse.getResults()).thenReturn(List.of(gen("5")));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        assertThat(scorer.evaluate("Spring Boot配置", "Spring Boot提供了多种配置方式...")).isEqualTo(5);
    }

    @Test
    void evaluate_whenLLMReturns1_returns1() {
        when(chatResponse.getResults()).thenReturn(List.of(gen("1")));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        assertThat(scorer.evaluate("Python Django", "Spring Boot是Java框架...")).isEqualTo(1);
    }

    @Test
    void evaluate_whenLLMReturnsNonNumeric_returns3() {
        when(chatResponse.getResults()).thenReturn(List.of(gen("irrelevant")));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        assertThat(scorer.evaluate("查询", "一些内容")).isEqualTo(3);
    }

    @Test
    void evaluate_whenLLMThrowsException_returns3() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("API error"));

        assertThat(scorer.evaluate("查询", "一些内容")).isEqualTo(3);
    }

    @Test
    void evaluate_whenContentEmpty_returns1() {
        assertThat(scorer.evaluate("查询", "")).isEqualTo(1);
    }

    @Test
    void evaluate_promptContainsQueryAndContent() {
        when(chatResponse.getResults()).thenReturn(List.of(gen("4")));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        scorer.evaluate("Spring Boot入门", "Spring Boot简化了Spring应用开发...");

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        String promptText = captor.getValue().getInstructions().getFirst().getText();
        assertThat(promptText).contains("Spring Boot入门");
        assertThat(promptText).contains("Spring Boot简化了Spring应用开发...");
        assertThat(promptText).contains("1-5");
    }
}
