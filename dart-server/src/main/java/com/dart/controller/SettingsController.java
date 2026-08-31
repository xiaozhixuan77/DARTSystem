package com.dart.controller;

import com.dart.dto.response.ApiResponse;
import com.dart.service.ApiConfigService;
import com.dart.service.SwitchableChatModel;
import org.springframework.beans.factory.ObjectProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置：chat API 配置查看/更换（运行时生效）、API 余额查询、数据池列表。
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    public record ApiConfigRequest(String baseUrl, String model, String apiKey) {}

    public record ApiConfigResponse(String baseUrl, String model, String apiKey) {}

    public record BalanceResponse(boolean isAvailable, String currency, String totalBalance, String error) {}

    private final ApiConfigService apiConfigService;
    private final ObjectProvider<SwitchableChatModel> switchableChatModelProvider;

    @GetMapping("/api")
    public ApiResponse<ApiConfigResponse> apiConfig() {
        ApiConfigService.ApiConfigView cfg = apiConfigService.get();
        if (cfg == null) {
            return ApiResponse.ok(null);
        }
        return ApiResponse.ok(new ApiConfigResponse(
                cfg.baseUrl(), cfg.model(), cfg.maskedApiKey()));
    }

    @PutMapping("/api")
    public ApiResponse<Void> updateApi(@RequestBody ApiConfigRequest request) {
        apiConfigService.save(request.baseUrl(), request.model(), request.apiKey());
        // 动态模型在 dev 存在；test/perf 等 profile 可能缺失（保存配置仍生效）
        SwitchableChatModel switcher = switchableChatModelProvider.getIfAvailable();
        if (switcher != null) {
            switcher.switchTo(request.baseUrl(), request.model(), request.apiKey());
        }
        return ApiResponse.ok(null);
    }

    @PostMapping("/test")
    public ApiResponse<SwitchableChatModel.ConnectionResult> test(@RequestBody ApiConfigRequest request) {
        SwitchableChatModel switcher = switchableChatModelProvider.getIfAvailable();
        if (switcher == null) {
            return ApiResponse.ok(new SwitchableChatModel.ConnectionResult(false, 0, "连接测试不可用"));
        }
        return ApiResponse.ok(switcher.testConnection(
                request.baseUrl(), request.model(), request.apiKey()));
    }

    @GetMapping("/balance")
    public ApiResponse<BalanceResponse> balance() {
        ApiConfigService.BalanceResult result = apiConfigService.queryBalance();
        return ApiResponse.ok(new BalanceResponse(
                result.isAvailable(), result.currency(), result.totalBalance(), result.error()));
    }

}
