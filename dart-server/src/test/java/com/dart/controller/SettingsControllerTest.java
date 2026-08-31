package com.dart.controller;

import com.dart.service.ApiConfigService;
import com.dart.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SettingsControllerTest {

    @Mock
    private ApiConfigService apiConfigService;
    @Mock
    private UserMapper userMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SettingsController(apiConfigService, userMapper))
                .build();
    }

    @Test
    void getApiConfig_returnsMaskedConfig() throws Exception {
        when(apiConfigService.get()).thenReturn(new ApiConfigService.ApiConfigView(
                "https://api.deepseek.com", "deepseek-v4-flash", "sk-abcdef12345"));

        mockMvc.perform(get("/api/settings/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseUrl").value("https://api.deepseek.com"))
                .andExpect(jsonPath("$.data.model").value("deepseek-v4-flash"))
                .andExpect(jsonPath("$.data.apiKey").value("sk-a****2345"));
    }

    @Test
    void putApiConfig_savesAndSwitches() throws Exception {
        mockMvc.perform(put("/api/settings/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"https://127.0.0.1:8080\","
                                + "\"model\":\"my-model\",\"apiKey\":\"sk-new-key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(apiConfigService).save("https://127.0.0.1:8080", "my-model", "sk-new-key");
    }

    @Test
    void getBalance_returnsParsedBalance() throws Exception {
        when(apiConfigService.queryBalance()).thenReturn(
                new ApiConfigService.BalanceResult(true, "CNY", "30.13", null));

        mockMvc.perform(get("/api/settings/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isAvailable").value(true))
                .andExpect(jsonPath("$.data.currency").value("CNY"))
                .andExpect(jsonPath("$.data.totalBalance").value("30.13"));
    }

    @Test
    void getBalance_queryFails_returnsErrorState() throws Exception {
        when(apiConfigService.queryBalance()).thenReturn(
                new ApiConfigService.BalanceResult(false, null, null, "余额查询失败：401"));

        mockMvc.perform(get("/api/settings/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isAvailable").value(false))
                .andExpect(jsonPath("$.data.error").value("余额查询失败：401"));
    }

    @Test
    void getPools_returnsExistingPools() throws Exception {
        when(userMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/settings/pools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
