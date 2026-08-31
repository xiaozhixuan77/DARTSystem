package com.dart.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dart.config.PoolInitializer;
import com.dart.dto.response.ApiResponse;
import com.dart.entity.User;
import com.dart.mapper.UserMapper;
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

    public record PoolResponse(String key, long id, String name) {}

    private final ApiConfigService apiConfigService;
    private final ObjectProvider<SwitchableChatModel> switchableChatModelProvider;
    private final UserMapper userMapper;

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

    @GetMapping("/balance")
    public ApiResponse<BalanceResponse> balance() {
        ApiConfigService.BalanceResult result = apiConfigService.queryBalance();
        return ApiResponse.ok(new BalanceResponse(
                result.isAvailable(), result.currency(), result.totalBalance(), result.error()));
    }

    @GetMapping("/pools")
    public ApiResponse<List<PoolResponse>> pools() {
        List<PoolResponse> pools = new ArrayList<>();
        pools.add(new PoolResponse("user", PoolInitializer.USER_POOL_USER_ID, "用户数据"));
        // 测试数据池仅开发期存在（pool_test 用户未创建则不返回，生产无此池）
        User testUser = userMapper.selectOne(
                new QueryWrapper<User>().eq("username", PoolInitializer.TEST_POOL_USERNAME));
        if (testUser != null) {
            pools.add(new PoolResponse("test", testUser.getId(), "测试数据"));
        }
        return ApiResponse.ok(pools);
    }
}
