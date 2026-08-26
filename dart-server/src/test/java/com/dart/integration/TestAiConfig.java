package com.dart.integration;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.Mockito.mock;

/**
 * 测试用 AI Bean 配置 —— 替代 Spring AI 自动配置，提供 Mock 实现。
 */
@TestConfiguration
public class TestAiConfig {

    @Bean
    @Primary
    public ChatModel chatModel() {
        return mock(ChatModel.class);
    }

    @Bean
    @Primary
    public EmbeddingModel embeddingModel() {
        return mock(EmbeddingModel.class);
    }
}
