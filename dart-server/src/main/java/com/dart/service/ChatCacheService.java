package com.dart.service;

import com.dart.agent.curator.CuratedReport;
import com.dart.broadcast.SseSink;
import com.dart.entity.Message;
import com.dart.util.RedisKeyConstants;
import com.dart.util.SseEvents;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 报告查询缓存：规范化 key 读写，命中直接推送并持久化消息。
 */
@Service
public class ChatCacheService {

    private static final Logger log = LoggerFactory.getLogger(ChatCacheService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final StringRedisTemplate redisTemplate;
    private final MessagePersistenceService messagePersistenceService;

    @Value("${agent.cache.ttl-hours:24}")
    private long cacheTtlHours;

    public ChatCacheService(StringRedisTemplate redisTemplate,
                            MessagePersistenceService messagePersistenceService) {
        this.redisTemplate = redisTemplate;
        this.messagePersistenceService = messagePersistenceService;
    }

    /**
     * 读取报告缓存：命中则 SSE 推送并持久化消息，返回结果；未命中返回 null。
     * 缓存 key 为原始查询（trim + 小写）的 SHA-256 前 8 字节十六进制。
     */
    public ChatService.SearchResult tryCache(Long userId, String query, Long conversationId, SseSink sink) {
        try {
            String json = redisTemplate.opsForValue().get(cacheKey(userId, query));
            if (json == null || json.isBlank()) {
                return null;
            }
            JsonNode node = OBJECT_MAPPER.readTree(json);
            String markdown = node.path("markdown").asText();
            if (markdown.isBlank()) {
                return null;
            }
            List<String> sources = new ArrayList<>();
            node.path("sources").forEach(s -> sources.add(s.asText()));
            CuratedReport report = new CuratedReport(query, markdown, sources, Instant.now());
            ChatService.SearchResult result = ChatService.SearchResult.searchResult(report, List.of());
            sink.sendEvent(SseEvents.CONTENT, markdown);
            messagePersistenceService.persistAssistantMessage(conversationId, result);
            log.info("报告缓存命中: query={}", query);
            return result;
        } catch (Exception e) {
            log.warn("读取报告缓存失败，继续完整流程", e);
            return null;
        }
    }

    /**
     * 写入报告缓存（仅搜索路径；RAG 回答与用户知识库相关，不缓存）。
     */
    public void writeCache(Long userId, String query, String markdown, List<String> sources) {
        try {
            Map<String, Object> data = Map.of("markdown", markdown, "sources", sources);
            redisTemplate.opsForValue().set(cacheKey(userId, query),
                    OBJECT_MAPPER.writeValueAsString(data), Duration.ofHours(cacheTtlHours));
        } catch (Exception e) {
            log.warn("写入报告缓存失败", e);
        }
    }

    private String cacheKey(Long userId, String query) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((userId + "|" + query.trim().toLowerCase()).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(RedisKeyConstants.REPORT_CACHE_PREFIX);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
