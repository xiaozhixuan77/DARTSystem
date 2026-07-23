package com.dart.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 手动创建 ChatModel / EmbeddingModel Bean，替代被排除的 Spring AI 2.0.0 自动配置。
 * Spring AI 2.0.0 依赖 Spring Framework 7.x（RetryTemplate/Jackson 3.x API），
 * 与当前 Spring Boot 3.4.1（Spring Framework 6.2.1）不兼容。
 *
 * 仅非测试 Profile 激活（测试环境使用 TestAiConfig 的 Mock Bean）。
 */
@Configuration
@Profile("!test")
public class AiModelConfig {

    private static final Logger log = LoggerFactory.getLogger(AiModelConfig.class);

    @Value("${spring.ai.deepseek.api-key}")
    private String deepseekApiKey;

    @Value("${spring.ai.deepseek.base-url:https://api.deepseek.com}")
    private String deepseekBaseUrl;

    @Value("${spring.ai.deepseek.chat.options.model:deepseek-chat}")
    private String deepseekModel;

    @Value("${spring.ai.openai.api-key}")
    private String openaiApiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String openaiBaseUrl;

    @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}")
    private String embeddingModel;

    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel chatModel(RestTemplate restTemplate) {
        log.info("使用手动配置的 DeepSeek ChatModel（绕过 Spring AI 2.0.0 自动配置）");
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                String content = prompt.getInstructions().stream()
                        .map(m -> m.getText())
                        .reduce("", (a, b) -> a + "\n" + b);
                Map<String, Object> body = Map.of(
                        "model", deepseekModel,
                        "messages", List.of(Map.of("role", "user", "content", content)),
                        "temperature", 0.7,
                        "max_tokens", 4096
                );

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(deepseekApiKey);

                ResponseEntity<Map> resp = restTemplate.postForEntity(
                        deepseekBaseUrl + "/v1/chat/completions",
                        new HttpEntity<>(body, headers), Map.class);

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices =
                        (List<Map<String, Object>>) resp.getBody().get("choices");
                String text = (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");

                return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
            }

            @Override
            public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
                return org.springframework.ai.chat.prompt.ChatOptions.builder()
                        .model(deepseekModel).build();
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel embeddingModel(RestTemplate restTemplate) {
        log.info("使用手动配置的 OpenAI EmbeddingModel（绕过 Spring AI 2.0.0 自动配置）");
        return new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                List<String> texts = request.getInstructions();
                Map<String, Object> body = Map.of(
                        "model", embeddingModel,
                        "input", texts.size() == 1 ? texts.get(0) : texts
                );

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(openaiApiKey);

                ResponseEntity<Map> resp = restTemplate.postForEntity(
                        openaiBaseUrl + "/v1/embeddings",
                        new HttpEntity<>(body, headers), Map.class);

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> data =
                        (List<Map<String, Object>>) resp.getBody().get("data");
                List<Embedding> embeddings = new ArrayList<>();
                for (Map<String, Object> item : data) {
                    @SuppressWarnings("unchecked")
                    List<Double> vec = (List<Double>) item.get("embedding");
                    float[] arr = new float[vec.size()];
                    for (int i = 0; i < vec.size(); i++) arr[i] = vec.get(i).floatValue();
                    embeddings.add(new Embedding(arr, (Integer) item.get("index")));
                }

                return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata());
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                return call(new EmbeddingRequest(texts, null))
                        .getResults().stream().map(Embedding::getOutput).toList();
            }

            @Override
            public float[] embed(org.springframework.ai.document.Document document) {
                return embed(List.of(document.getText())).get(0);
            }

            @Override
            public EmbeddingResponse embedForResponse(List<String> texts) {
                return call(new EmbeddingRequest(texts, null));
            }
        };
    }
}
