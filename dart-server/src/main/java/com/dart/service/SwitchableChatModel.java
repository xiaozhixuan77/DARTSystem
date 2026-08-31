package com.dart.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import jakarta.annotation.PostConstruct;

/**
 * 可切换 ChatModel（@Primary）：业务全部注入点无感知接管。
 * 初始委托 Spring AI 自动配置的默认模型 bean（deepseekChatModel）；
 * 设置页保存 API 配置后 switchTo 编程式重建（OpenAI 兼容，DeepSeek 走 OpenAiApi）。
 */
@Slf4j
@Service
@Primary
@Profile("!test")
@SuppressWarnings("all")
public class SwitchableChatModel implements ChatModel {

    private final ApiConfigService apiConfigService;
    private final ApplicationContext applicationContext;

    @org.springframework.beans.factory.annotation.Value("${spring.ai.deepseek.chat.options.temperature:0.7}")
    private double defaultTemperature;

    private volatile ChatModel delegate;

    public SwitchableChatModel(ApiConfigService apiConfigService, @Lazy ApplicationContext applicationContext) {
        this.apiConfigService = apiConfigService;
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        // 持久化配置存在 → 用之构建；否则用自动配置默认 bean（读取启动 yml/.env）
        var cfg = apiConfigService.get();
        if (cfg != null) {
            delegate = buildOpenAiModel(cfg.baseUrl(), cfg.model(), cfg.apiKey());
            log.info("chat 模型从持久化配置加载: baseUrl={}, model={}", cfg.baseUrl(), cfg.model());
        } else {
            delegate = findDefaultChatModel();
            log.info("chat 模型使用默认 bean: {}", delegate.getClass().getSimpleName());
        }
    }

    /** 默认模型查找：优先官方 bean 名（deepseekChatModel），否则遍历排除自身（测试 mock 兼容）。 */
    private ChatModel findDefaultChatModel() {
        if (applicationContext.containsBean("deepseekChatModel")) {
            return applicationContext.getBean("deepseekChatModel", ChatModel.class);
        }
        return applicationContext.getBeansOfType(ChatModel.class).values().stream()
                .filter(m -> !(m instanceof SwitchableChatModel))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("无可用 ChatModel bean"));
    }

    public void switchTo(String baseUrl, String model, String apiKey) {
        delegate = buildOpenAiModel(baseUrl, model, apiKey);
        log.info("chat 模型已切换: baseUrl={}, model={}", baseUrl, model);
    }

    /** 直接替换委托（测试与切换共用）。 */
    public void switchDelegate(ChatModel model) {
        this.delegate = model;
    }

    private ChatModel buildOpenAiModel(String baseUrl, String model, String apiKey) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(defaultTemperature)
                .build();
        return new OpenAiChatModel(api, options,
                org.springframework.ai.model.tool.ToolCallingManager.builder().build(),
                new org.springframework.retry.support.RetryTemplate(),
                io.micrometer.observation.ObservationRegistry.create());
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt);
    }

    @Override
    public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
