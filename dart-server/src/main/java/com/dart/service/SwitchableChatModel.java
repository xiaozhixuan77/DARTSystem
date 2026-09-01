package com.dart.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.ApplicationContext;
import com.dart.util.UrlUtils;
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
            delegate = buildOpenAiModel(normalizeBaseUrl(cfg.baseUrl()), cfg.model(), cfg.apiKey());
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
        delegate = buildOpenAiModel(normalizeBaseUrl(baseUrl), model, apiKey);
        log.info("chat 模型已切换: baseUrl={}, model={}", baseUrl, model);
    }

    public record ConnectionResult(boolean ok, long latencyMs, String error) {}

    /**
     * 连接测试：用给定配置临时构建模型并真实调用一次（"ping"），
     * 不切换当前委托——供保存前验证连通性（失败则前端口不保存，兜底防配置损坏）。
     */
    public ConnectionResult testConnection(String baseUrl, String model, String apiKey) {
        long start = System.nanoTime();
        String normalized = normalizeBaseUrl(baseUrl);
        try {
            ChatModel testModel = buildOpenAiModel(normalized, model, apiKey);
            String resp = testModel.call(new Prompt(new UserMessage("ping")))
                    .getResults().getFirst().getOutput().getText();
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.info("连接测试成功: model={}, latency={}ms, resp={}", model, ms,
                    resp == null ? "null" : resp.substring(0, Math.min(20, resp.length())));
            return new ConnectionResult(true, ms, null);
        } catch (Exception e) {
            log.warn("连接测试失败: model={} ({})", model, e.getMessage());
            return new ConnectionResult(false, 0, friendlyConnectionError(e));
        }
    }

    /** 连接测试异常 → 用户可读文案（绝不泄露响应 body JSON 堆）。 */
    static String friendlyConnectionError(Throwable e) {
        if (e instanceof org.springframework.web.client.HttpClientErrorException http) {
            return switch (http.getStatusCode().value()) {
                case 401, 403 -> "连接失败：API Key 无效（HTTP " + http.getStatusCode().value() + "）";
                case 404 -> "连接失败：服务地址不正确（请填写服务根地址，不要带 /anthropic 等路径后缀）";
                default -> "连接失败（HTTP " + http.getStatusCode().value() + "）";
            };
        }
        // Spring AI 调用层异常 message 形如 "401 - {body}"——字符串识别后映射，body 不回流
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("401") || msg.contains("Authentication Fails") || msg.contains("invalid")) {
            return "连接失败：API Key 无效（请检查 Key）";
        }
        if (msg.contains("404") || msg.contains("url_not_found")) {
            return "连接失败：服务地址不正确（请填写服务根地址，不要带 /anthropic 等路径后缀）";
        }
        if (msg.contains("timed out") || msg.contains("timeout")) {
            return "连接失败：请求超时（请检查网络或服务地址）";
        }
        if (msg.contains("Connection refused") || msg.contains("Failed to connect")) {
            return "连接失败：无法连接到服务地址（请检查 URL）";
        }
        return "连接失败：请检查服务地址与 API Key";
    }

    /** 剥去 Anthropic 兼容路径后缀（DeepSeek/Moonshot 官方 /anthropic 端点），
     *  底层同一服务走 OpenAI 协议用根地址——用户填官方 Anthropic 地址也能适配。 */
    static String normalizeBaseUrl(String url) {
        String trimmed = UrlUtils.trimTrailingSlash(url);
        if (trimmed == null) {
            return null;
        }
        if (trimmed.toLowerCase().endsWith("/anthropic")) {
            return trimmed.substring(0, trimmed.length() - "/anthropic".length());
        }
        return trimmed;
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
