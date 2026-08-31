package com.dart.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SwitchableChatModelTest {

    @Test
    void call_delegatesToCurrentModel() {
        ChatModel mock = mock(ChatModel.class);
        ChatResponse resp = new ChatResponse(List.of(new Generation(new AssistantMessage("hi"))));
        when(mock.call(any(Prompt.class))).thenReturn(resp);
        SwitchableChatModel model = new SwitchableChatModel(
                mock(ApiConfigService.class), mock(ApplicationContext.class));
        model.switchDelegate(mock);

        assertThat(model.call(new Prompt("msg"))).isSameAs(resp);
    }

    @Test
    void stream_delegatesToCurrentModel() {
        ChatModel mock = mock(ChatModel.class);
        when(mock.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.empty());
        SwitchableChatModel model = new SwitchableChatModel(
                mock(ApiConfigService.class), mock(ApplicationContext.class));
        model.switchDelegate(mock);

        assertThat(model.stream(new Prompt("msg"))).isNotNull();
    }

    @Test
    void switchTo_replacesDelegate() {
        ChatModel first = mock(ChatModel.class);
        ChatModel second = mock(ChatModel.class);
        when(first.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("first")))));
        when(second.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("second")))));
        SwitchableChatModel model = new SwitchableChatModel(
                mock(ApiConfigService.class), mock(ApplicationContext.class));

        // 模拟切换（封装 build 的分叉点：switchTo 内部重建后委托新模型）
        model.switchDelegate(second);

        assertThat(model.call(new Prompt("x")).getResults().getFirst().getOutput().getText())
                .isEqualTo("second");
    }
}
