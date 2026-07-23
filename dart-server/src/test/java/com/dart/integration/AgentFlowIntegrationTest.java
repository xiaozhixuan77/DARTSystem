package com.dart.integration;

import com.dart.service.ChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class AgentFlowIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ChatService chatService;

    @Test
    @DisplayName("非技术查询返回 NON_TECH_QUERY 错误")
    void nonTechQueryReturnsError() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("NO")))));

        List<String> events = new ArrayList<>();
        SseEmitter emitter = new SseEmitter();
        emitter.onCompletion(() -> events.add("complete"));
        emitter.onError(ex -> events.add("error:" + ex.getMessage()));

        ChatService.SearchResult result = chatService.executeSearchAndCurate(
                "今天天气怎么样", 1L, emitter);

        assertThat(result.isRagHit()).isFalse();
        assertThat(result.curatedReport()).isNotNull();
        assertThat(result.curatedReport().markdown()).contains("仅支持技术");

        Mockito.reset(chatModel);
    }

    @Test
    @DisplayName("LLM 调用异常时 isTechQuery 降级返回 true")
    void isTechQueryFallsBackToTrueWhenLlmFails() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("LLM timeout"));

        boolean isTech = chatService.isTechQuery("Spring Boot 配置问题");
        assertThat(isTech).isTrue();

        Mockito.reset(chatModel);
    }

    @Test
    @DisplayName("技术查询判定通过")
    void techQueryReturnsTrue() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("YES")))));

        boolean isTech = chatService.isTechQuery("Spring Boot configuration");
        assertThat(isTech).isTrue();

        Mockito.reset(chatModel);
    }
}
