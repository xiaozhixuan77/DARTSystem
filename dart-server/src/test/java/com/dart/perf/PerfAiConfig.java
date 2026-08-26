package com.dart.perf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;

/**
 * 压测专用 AI 配置：
 * - ChatModel 全程 mock（检索测速与精准度均不依赖答案生成）
 * - EmbeddingModel 为可切换实现——速度阶段用 mock 填充向量（毫秒级造数，延迟与向量值无关），
 *   精准度阶段切到真实硅基流动 API（OpenAI 兼容 /v1/embeddings，需 SILICONFLOW_API_KEY）。
 * perf profile 下 main 的 AiModelConfig（@Profile("!test") 且 @ConditionalOnMissingBean）不生效。
 */
@TestConfiguration
public class PerfAiConfig {

    private static final Logger log = LoggerFactory.getLogger(PerfAiConfig.class);

    @Bean
    @Primary
    public ChatModel chatModel() {
        return mock(ChatModel.class);
    }

    @Bean
    @Primary
    public EmbeddingModel perfEmbeddingModel(RestTemplate restTemplate,
                                             @Value("${spring.ai.openai.base-url}") String baseUrl,
                                             @Value("${spring.ai.openai.api-key:}") String apiKey,
                                             @Value("${spring.ai.openai.embedding.options.model}") String model) {
        return new SwitchableEmbeddingModel(restTemplate, baseUrl, apiKey, model);
    }

    /**
     * 可切换 EmbeddingModel：mock 模式（填充向量）/ 真实模式（硅基流动 API）。
     */
    public static class SwitchableEmbeddingModel implements EmbeddingModel {

        private final RestTemplate restTemplate;
        private final String baseUrl;
        private final String apiKey;
        private final String model;

        private volatile boolean mockMode = true;
        private volatile float[] mockVector = new float[1024];

        public SwitchableEmbeddingModel(RestTemplate restTemplate, String baseUrl,
                                        String apiKey, String model) {
            this.restTemplate = restTemplate;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
            for (int i = 0; i < mockVector.length; i++) {
                mockVector[i] = 0.01f;
            }
        }

        public void setMockMode(boolean mockMode) {
            this.mockMode = mockMode;
            log.info("SwitchableEmbeddingModel mockMode={} (真实 API key {})",
                    mockMode, apiKey == null || apiKey.isBlank() ? "缺失" : "已注入");
        }

        public boolean isRealApiAvailable() {
            return apiKey != null && !apiKey.isBlank();
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            if (mockMode) {
                List<Embedding> embs = new ArrayList<>();
                for (int i = 0; i < request.getInstructions().size(); i++) {
                    embs.add(new Embedding(mockVector, i));
                }
                return new EmbeddingResponse(embs, new EmbeddingResponseMetadata());
            }
            return realCall(request);
        }

        private EmbeddingResponse realCall(EmbeddingRequest request) {
            List<String> texts = request.getInstructions();
            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", texts.size() == 1 ? texts.get(0) : texts
            );
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            @SuppressWarnings("unchecked")
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    baseUrl + "/v1/embeddings",
                    new HttpEntity<>(body, headers), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) resp.getBody().get("data");
            List<Embedding> embs = new ArrayList<>();
            for (Map<String, Object> item : data) {
                @SuppressWarnings("unchecked")
                List<Double> vec = (List<Double>) item.get("embedding");
                float[] arr = new float[vec.size()];
                for (int i = 0; i < vec.size(); i++) {
                    arr[i] = vec.get(i).floatValue();
                }
                embs.add(new Embedding(arr, (Integer) item.get("index")));
            }
            return new EmbeddingResponse(embs, new EmbeddingResponseMetadata());
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            return call(new EmbeddingRequest(texts, null))
                    .getResults().stream().map(Embedding::getOutput).toList();
        }

        @Override
        public float[] embed(Document document) {
            return embed(List.of(document.getText())).get(0);
        }

        @Override
        public EmbeddingResponse embedForResponse(List<String> texts) {
            return call(new EmbeddingRequest(texts, null));
        }
    }
}
