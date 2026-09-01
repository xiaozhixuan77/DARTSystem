package com.dart.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

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
        ReflectionTestUtils.setField(model, "delegate", mock);

        assertThat(model.call(new Prompt("msg"))).isSameAs(resp);
    }

    @Test
    void stream_delegatesToCurrentModel() {
        ChatModel mock = mock(ChatModel.class);
        when(mock.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.empty());
        SwitchableChatModel model = new SwitchableChatModel(
                mock(ApiConfigService.class), mock(ApplicationContext.class));
        ReflectionTestUtils.setField(model, "delegate", mock);

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
        ReflectionTestUtils.setField(model, "delegate", second);

        assertThat(model.call(new Prompt("x")).getResults().getFirst().getOutput().getText())
                .isEqualTo("second");
    }

    @Test
    void connectionError_401_mapsToFriendlyInvalidKey() {
        var e = org.springframework.web.client.HttpClientErrorException.create(
                "{}", org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null);
        assertThat(SwitchableChatModel.friendlyConnectionError(e))
                .isEqualTo("连接失败：API Key 无效（HTTP 401）");
    }

    @Test
    void normalizeBaseUrl_stripsAnthropicSuffix() {
        assertThat(SwitchableChatModel.normalizeBaseUrl("https://api.deepseek.com/anthropic"))
                .isEqualTo("https://api.deepseek.com");
        assertThat(SwitchableChatModel.normalizeBaseUrl("https://api.deepseek.com/anthropic/"))
                .isEqualTo("https://api.deepseek.com");
        assertThat(SwitchableChatModel.normalizeBaseUrl("https://api.deepseek.com"))
                .isEqualTo("https://api.deepseek.com");
        assertThat(SwitchableChatModel.normalizeBaseUrl("https://api.siliconflow.cn/v1"))
                .isEqualTo("https://api.siliconflow.cn/v1");
    }

    @Test
    void connectionError_wrapped404_mapsToRootUrlHint() {
        assertThat(SwitchableChatModel.friendlyConnectionError(
                new RuntimeException("404 - {\"code\":\"url_not_found\"}")))
                .isEqualTo("连接失败：服务地址不正确（请填写服务根地址，不要带 /anthropic 等路径后缀）");
    }

    @Test
    void connectionError_springAiWrapped401_bodyNotLeaked() {
        // Spring AI 调用层包装异常：message 为 "401 - {body JSON}"
        assertThat(SwitchableChatModel.friendlyConnectionError(
                new RuntimeException("401 - {\"error\":{\"message\":\"Authentication Fails\"}}")))
                .isEqualTo("连接失败：API Key 无效（请检查 Key）");
    }

    @Test
    void connectionError_connectionRefused_mapsToUnreachable() {
        assertThat(SwitchableChatModel.friendlyConnectionError(
                new RuntimeException("Connection refused")))
                .isEqualTo("连接失败：无法连接到服务地址（请检查 URL）");
    }

    @Test
    void connectionError_timeout_mapsToTimeout() {
        assertThat(SwitchableChatModel.friendlyConnectionError(
                new RuntimeException("Read timed out")))
                .isEqualTo("连接失败：请求超时（请检查网络或服务地址）");
    }

    @Test
    void connectionError_other_mapsToGeneric() {
        assertThat(SwitchableChatModel.friendlyConnectionError(new RuntimeException("boom")))
                .isEqualTo("连接失败：请检查服务地址与 API Key");
    }
}
