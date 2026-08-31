package com.dart.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiConfigServiceTest {

    @Test
    void friendlyBalanceError_404_mapsToMissingEndpoint() {
        var e = HttpClientErrorException.create(
                "{\"error\":\"not found\"}", org.springframework.http.HttpStatus.NOT_FOUND,
                "Not Found", null, null, null);
        assertThat(ApiConfigService.friendlyBalanceError(e))
                .isEqualTo("该服务地址无余额查询端点（仅 DeepSeek /user/balance 支持）");
    }

    @Test
    void friendlyBalanceError_401_mapsToInvalidKey() {
        var e = HttpClientErrorException.create(
                "{}", org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null);
        assertThat(ApiConfigService.friendlyBalanceError(e))
                .isEqualTo("API Key 无效（HTTP 401）");
    }

    @Test
    void friendlyBalanceError_otherHttp_mapsToStatus() {
        var e = HttpClientErrorException.create(
                "{}", org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Error", null, null, null);
        assertThat(ApiConfigService.friendlyBalanceError(e))
                .isEqualTo("余额查询失败（HTTP 500）");
    }

    @Test
    void friendlyBalanceError_networkError_mapsToNetwork() {
        assertThat(ApiConfigService.friendlyBalanceError(new RuntimeException("timeout")))
                .isEqualTo("余额查询失败（网络错误）");
    }
}
