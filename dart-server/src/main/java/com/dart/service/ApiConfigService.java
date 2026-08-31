package com.dart.service;

import com.dart.entity.AppApiConfig;
import com.dart.mapper.AppApiConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;

/**
 * chat API 运行时配置：读取/保存（单行 id=1），余额查询（DeepSeek /user/balance）。
 */
@Slf4j
@Service
public class ApiConfigService {

    private static final long CONFIG_ID = 1L;

    public record ApiConfigView(String baseUrl, String model, String apiKey) {
        public String maskedApiKey() {
            if (apiKey == null || apiKey.length() < 10) {
                return apiKey == null ? "" : "****";
            }
            return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
        }
    }

    public record BalanceResult(boolean isAvailable, String currency, String totalBalance, String error) {}

    private final AppApiConfigMapper configMapper;

    @org.springframework.beans.factory.annotation.Value("${spring.ai.deepseek.base-url:https://api.deepseek.com}")
    private String defaultBaseUrl;

    @org.springframework.beans.factory.annotation.Value("${spring.ai.deepseek.api-key:}")
    private String defaultApiKey;

    public ApiConfigService(AppApiConfigMapper configMapper) {
        this.configMapper = configMapper;
    }

    public ApiConfigView get() {
        AppApiConfig cfg = configMapper.selectById(CONFIG_ID);
        if (cfg == null) {
            return null;
        }
        return new ApiConfigView(cfg.getChatBaseUrl(), cfg.getChatModel(), cfg.getChatApiKey());
    }

    /** 保存配置并立即重建模型（调用方 SwitchableChatModel 提供回切）。 */
    public void save(String baseUrl, String model, String apiKey) {
        // 外部输入可能带首尾空白（CRLF 文件提取等），trim 防污染
        baseUrl = baseUrl == null ? null : baseUrl.trim();
        model = model == null ? null : model.trim();
        apiKey = apiKey == null ? null : apiKey.trim();
        AppApiConfig cfg = configMapper.selectById(CONFIG_ID);
        if (cfg == null) {
            cfg = new AppApiConfig();
            cfg.setId(CONFIG_ID);
            cfg.setChatBaseUrl(baseUrl);
            cfg.setChatModel(model);
            cfg.setChatApiKey(apiKey);
            cfg.setUpdatedAt(LocalDateTime.now());
            configMapper.insert(cfg);
            return;
        }
        cfg.setChatBaseUrl(baseUrl);
        cfg.setChatModel(model);
        cfg.setChatApiKey(apiKey);
        cfg.setUpdatedAt(LocalDateTime.now());
        configMapper.updateById(cfg);
    }

    /**
     * 查询 chat API 余额：GET {baseUrl}/user/balance（DeepSeek 接口，Bearer key）。
     * 失败返回 isAvailable=false + error 文案（前端展示"查询失败"原因）。
     */
    public BalanceResult queryBalance() {
        // 未配置持久化配置时回退启动默认（yml deepseek base-url/api-key）
        ApiConfigView cfg = get();
        if (cfg == null) {
            cfg = new ApiConfigView(defaultBaseUrl, "", defaultApiKey);
        }
        try {
            String base = cfg.baseUrl() == null ? "" : cfg.baseUrl().replaceAll("/+$", "");
            String body = RestClient.create(base)
                    .get()
                    .uri("/user/balance")
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .retrieve()
                    .body(String.class);
            return parseBalance(body);
        } catch (Exception e) {
            log.warn("余额查询失败: {}", e.getMessage());
            return new BalanceResult(false, null, null, friendlyBalanceError(e));
        }
    }

    /**
     * 余额查询异常 → 友好文案（不泄露响应 body JSON 堆）。
     * 余额为 DeepSeek 专用 /user/balance 端点；其他兼容 API（Kimi/OpenAI 等）无该端点。
     */
    static String friendlyBalanceError(Throwable e) {
        if (e instanceof org.springframework.web.client.HttpClientErrorException http) {
            return switch (http.getStatusCode().value()) {
                case 401, 403 -> "API Key 无效（HTTP " + http.getStatusCode().value() + "）";
                case 404 -> "该服务地址无余额查询端点（仅 DeepSeek /user/balance 支持）";
                default -> "余额查询失败（HTTP " + http.getStatusCode().value() + "）";
            };
        }
        return "余额查询失败（网络错误）";
    }

    private BalanceResult parseBalance(String body) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            boolean available = node.path("is_available").asBoolean(false);
            var infos = node.path("balance_infos");
            if (!infos.isEmpty()) {
                var first = infos.get(0);
                return new BalanceResult(available,
                        first.path("currency").asText(),
                        first.path("total_balance").asText(), null);
            }
            return new BalanceResult(available, null, null, "响应无余额字段");
        } catch (Exception e) {
            return new BalanceResult(false, null, null, "余额响应解析失败：" + e.getMessage());
        }
    }
}
