package com.dart.agent.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * SearXNG 搜索客户端。
 */
public class SearXNGClient {

    private static final Logger log = LoggerFactory.getLogger(SearXNGClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final ObjectMapper objectMapper;

    public SearXNGClient(RestTemplate restTemplate, String baseUrl, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.objectMapper = objectMapper;
    }

    public List<SearchResult> search(String query, int maxResults) {
        String url = baseUrl + "search?q=" + encode(query) + "&format=json&categories=it,software";
        try {
            String json = restTemplate.getForObject(url, String.class);
            if (json == null) {
                return List.of();
            }
            Map<String, Object> body = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) body.getOrDefault("results", List.of());
            return results.stream()
                    .map(r -> new SearchResult(
                            (String) r.getOrDefault("url", ""),
                            (String) r.getOrDefault("title", ""),
                            (String) r.getOrDefault("content", "")
                    ))
                    .limit(maxResults)
                    .toList();
        } catch (Exception e) {
            log.error("SearXNG 搜索失败: query={}", query, e);
            return List.of();
        }
    }

    private String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
