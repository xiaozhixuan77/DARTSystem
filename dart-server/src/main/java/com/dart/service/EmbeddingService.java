package com.dart.service;

import com.dart.agent.scoring.ScoredResource;
import com.dart.agent.splitter.ChunkResult;
import com.dart.agent.splitter.MarkdownSplitter;
import com.dart.entity.KnowledgeChunk;
import com.dart.entity.KnowledgeItem;
import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.mapper.KnowledgeItemMapper;
import com.dart.util.RedisKeyConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 文本分块 + Embedding 向量化 + MySQL+Redis 双写。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {1000, 2000, 4000};

    private final EmbeddingModel embeddingModel;
    private final MarkdownSplitter splitter;
    private final KnowledgeItemMapper knowledgeItemMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final StringRedisTemplate redisTemplate;

    public EmbeddingService(EmbeddingModel embeddingModel, MarkdownSplitter splitter,
                            KnowledgeItemMapper knowledgeItemMapper,
                            KnowledgeChunkMapper knowledgeChunkMapper,
                            StringRedisTemplate redisTemplate) {
        this.embeddingModel = embeddingModel;
        this.splitter = splitter;
        this.knowledgeItemMapper = knowledgeItemMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 对文本进行分块、向量化，写入 MySQL + Redis。
     */
    @Transactional
    public List<Long> embedAndPersist(Long userId, String title, String content,
                                       String sourceUrl, ScoredResource scores) {
        // Step 1: 保存 KnowledgeItem
        KnowledgeItem item = new KnowledgeItem();
        item.setUserId(userId);
        item.setTitle(title);
        item.setContent(content);
        item.setSourceUrl(sourceUrl);
        item.setSourceUrlHash(sha256(sourceUrl));
        item.setScoreT(scores.getScoreT());
        item.setScoreA(scores.getScoreA());
        item.setScoreD(scores.getScoreD());
        item.setScoreM(scores.getScoreM());
        item.setWeightedScore(BigDecimal.valueOf(scores.getWeightedScore())
                .setScale(2, RoundingMode.HALF_UP));
        item.setIsDeleted(0);
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        knowledgeItemMapper.insertKnowledgeItem(item);

        Long knowledgeItemId = item.getId();
        if (knowledgeItemId == null) {
            throw new IllegalStateException("KnowledgeItem ID 未生成");
        }

        // Step 2: 文本分块
        List<ChunkResult> chunks = splitter.split(content);
        if (chunks.isEmpty()) {
            return List.of(knowledgeItemId);
        }

        // Step 3: 批量 Embedding
        List<float[]> embeddings = new ArrayList<>();
        List<String> embeddingIds = new ArrayList<>();
        for (ChunkResult chunk : chunks) {
            try {
                float[] vec = embed(chunk.chunkText());
                embeddings.add(vec);
                String embeddingId = "chunk:" + knowledgeItemId + ":" + chunk.chunkIndex();
                embeddingIds.add(embeddingId);
            } catch (Exception e) {
                log.error("Embedding 失败: itemId={}, chunkIndex={}", knowledgeItemId, chunk.chunkIndex(), e);
                throw new RuntimeException("Embedding API 调用失败", e);
            }
        }

        // Step 4: 写入 KnowledgeChunk (MySQL)
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk kc = new KnowledgeChunk();
            kc.setKnowledgeItemId(knowledgeItemId);
            kc.setChunkIndex(chunks.get(i).chunkIndex());
            kc.setChunkText(chunks.get(i).chunkText());
            kc.setEmbeddingId(embeddingIds.get(i));
            kc.setCreatedAt(LocalDateTime.now());
            knowledgeChunkMapper.insertKnowledgeChunk(kc);
        }

        // Step 5: afterCommit 异步写 Redis（需在事务中）
        List<String> finalEmbeddingIds = List.copyOf(embeddingIds);
        List<float[]> finalEmbeddings = List.copyOf(embeddings);
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            writeToRedisWithRetry(finalEmbeddingIds, finalEmbeddings);
                        }
                    });
        } else {
            // 无事务时直接写入（测试环境）
            writeToRedisWithRetry(finalEmbeddingIds, finalEmbeddings);
        }

        return List.of(knowledgeItemId);
    }

    public float[] embed(String text) {
        var options = org.springframework.ai.embedding.DefaultEmbeddingOptions.builder().build();
        var request = new org.springframework.ai.embedding.EmbeddingRequest(List.of(text), options);
        var response = embeddingModel.call(request);
        return response.getResult().getOutput();
    }

    private void writeToRedisWithRetry(List<String> embeddingIds, List<float[]> embeddings) {
        for (int i = 0; i < embeddingIds.size(); i++) {
            String key = RedisKeyConstants.DART_PREFIX + embeddingIds.get(i);
            byte[] vectorBytes = floatArrayToBytes(embeddings.get(i));
            String vectorB64 = Base64.getEncoder().encodeToString(vectorBytes);

            for (int retry = 0; retry < MAX_RETRIES; retry++) {
                try {
                    redisTemplate.opsForHash().put(key, "embedding", vectorB64);
                    redisTemplate.opsForHash().put(key, "chunk_id", embeddingIds.get(i));
                    log.debug("Redis 写入成功: {}", key);
                    break;
                } catch (Exception e) {
                    log.warn("Redis 写入失败 (重试 {}/{}): {}", retry + 1, MAX_RETRIES, key, e);
                    if (retry < MAX_RETRIES - 1) {
                        try {
                            Thread.sleep(RETRY_DELAYS_MS[retry]);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }
    }

    private byte[] floatArrayToBytes(float[] floats) {
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    private String sha256(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 不可用", e);
        }
    }
}
