package com.dart.service;

import com.dart.agent.scoring.ScoredResource;
import com.dart.agent.splitter.ChunkResult;
import com.dart.agent.splitter.MarkdownSplitter;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
    /** 单次 embedding 请求最大文本数（OpenAI 兼容 input 数组） */
    private static final int EMBED_BATCH_SIZE = 32;

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
     *
     * @param tags 逗号分隔的标签（沉淀时为受控类别标签，可空）
     */
    @Transactional
    public List<Long> embedAndPersist(Long userId, String title, String content,
                                       String sourceUrl, ScoredResource scores, String tags) {
        // Step 1: 保存 KnowledgeItem
        KnowledgeItem item = new KnowledgeItem();
        item.setUserId(userId);
        item.setTitle(title);
        item.setContent(content);
        item.setSourceUrl(sourceUrl);
        item.setTags(tags);
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
        knowledgeItemMapper.insert(item);

        Long knowledgeItemId = item.getId();
        if (knowledgeItemId == null) {
            throw new IllegalStateException("KnowledgeItem ID 未生成");
        }

        // Step 2: 文本分块
        List<ChunkResult> chunks = splitter.split(content);
        if (chunks.isEmpty()) {
            return List.of(knowledgeItemId);
        }

        // Step 3: 批量 Embedding（一次请求多条，减少远程调用；失败回退逐条，仍失败抛异常终止）
        List<float[]> embeddings = embedAll(chunks, knowledgeItemId, "persist");
        List<String> embeddingIds = new ArrayList<>(chunks.size());
        for (ChunkResult chunk : chunks) {
            embeddingIds.add("chunk:" + knowledgeItemId + ":" + chunk.chunkIndex());
        }

        // Step 4: 批量写入 KnowledgeChunk (MySQL)，同时存储向量
        List<KnowledgeChunk> chunkEntities = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk kc = new KnowledgeChunk();
            kc.setKnowledgeItemId(knowledgeItemId);
            kc.setChunkIndex(chunks.get(i).chunkIndex());
            kc.setChunkText(chunks.get(i).chunkText());
            kc.setEmbeddingId(embeddingIds.get(i));
            kc.setEmbedding(Base64.getEncoder().encodeToString(
                    com.dart.util.VectorUtils.floatArrayToBytes(embeddings.get(i))));
            kc.setCreatedAt(LocalDateTime.now());
            chunkEntities.add(kc);
        }
        for (KnowledgeChunk kc : chunkEntities) {
            knowledgeChunkMapper.insert(kc);
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

    /**
     * 正文编辑后重建条目分块与向量：删除旧 chunk，新内容分块向量化并插入 MySQL，
     * afterCommit 先删旧 Redis key 再写新向量（失败则都不执行，MySQL/Redis 保持一致）。
     */
    @Transactional
    public void rebuildChunksForItem(Long knowledgeItemId, String content) {
        QueryWrapper<KnowledgeChunk> wrapper = new QueryWrapper<>();
        wrapper.eq("knowledge_item_id", knowledgeItemId);
        List<KnowledgeChunk> oldChunks = knowledgeChunkMapper.selectList(wrapper);
        List<String> oldEmbeddingIds = oldChunks.stream()
                .map(KnowledgeChunk::getEmbeddingId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        knowledgeChunkMapper.delete(wrapper);

        if (content == null || content.isBlank()) {
            runAfterCommit(() -> deleteRedisKeys(oldEmbeddingIds));
            return;
        }

        List<ChunkResult> chunks = splitter.split(content);
        List<float[]> embeddings = embedAll(chunks, knowledgeItemId, "rebuild");
        List<String> embeddingIds = new ArrayList<>(chunks.size());
        for (ChunkResult chunk : chunks) {
            embeddingIds.add("chunk:" + knowledgeItemId + ":" + chunk.chunkIndex());
        }
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk kc = new KnowledgeChunk();
            kc.setKnowledgeItemId(knowledgeItemId);
            kc.setChunkIndex(chunks.get(i).chunkIndex());
            kc.setChunkText(chunks.get(i).chunkText());
            kc.setEmbeddingId(embeddingIds.get(i));
            kc.setEmbedding(Base64.getEncoder().encodeToString(
                    com.dart.util.VectorUtils.floatArrayToBytes(embeddings.get(i))));
            kc.setCreatedAt(LocalDateTime.now());
            knowledgeChunkMapper.insert(kc);
        }

        List<String> finalOldIds = List.copyOf(oldEmbeddingIds);
        List<String> finalNewIds = List.copyOf(embeddingIds);
        List<float[]> finalNewVectors = List.copyOf(embeddings);
        runAfterCommit(() -> {
            deleteRedisKeys(finalOldIds);
            writeChunkVectorsToRedis(redisTemplate, finalNewIds, finalNewVectors);
        });
    }

    private void runAfterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
        } else {
            action.run();
        }
    }

    private void deleteRedisKeys(List<String> embeddingIds) {
        if (embeddingIds.isEmpty()) {
            return;
        }
        try {
            List<String> keys = embeddingIds.stream()
                    .map(id -> RedisKeyConstants.DART_PREFIX + id)
                    .toList();
            redisTemplate.delete(keys);
        } catch (Exception e) {
            log.warn("删除旧 Redis 向量失败（残留无害）: {} 条", embeddingIds.size(), e);
        }
    }

    public float[] embed(String text) {
        var options = org.springframework.ai.embedding.DefaultEmbeddingOptions.builder().build();
        var request = new org.springframework.ai.embedding.EmbeddingRequest(List.of(text), options);
        var response = embeddingModel.call(request);
        return response.getResult().getOutput();
    }

    /**
     * 批量向量化：一次请求多条文本（OpenAI 兼容 input 数组），减少远程调用次数；
     * 整批失败回退逐条（保持单条 fail-open 语义），逐条仍失败则抛异常。
     */
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> results = new ArrayList<>(texts.size());
        var options = org.springframework.ai.embedding.DefaultEmbeddingOptions.builder().build();
        for (int i = 0; i < texts.size(); i += EMBED_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + EMBED_BATCH_SIZE, texts.size()));
            List<float[]> vecs;
            try {
                var request = new org.springframework.ai.embedding.EmbeddingRequest(batch, options);
                vecs = embeddingModel.call(request).getResults().stream()
                        .map(org.springframework.ai.embedding.Embedding::getOutput).toList();
            } catch (Exception e) {
                log.warn("批量 embedding 失败({} 条)，回退逐条: {}", batch.size(), e.getMessage());
                vecs = new ArrayList<>(batch.size());
                for (String text : batch) {
                    vecs.add(embed(text));
                }
            }
            results.addAll(vecs);
        }
        return results;
    }

    private List<float[]> embedAll(List<ChunkResult> chunks, Long knowledgeItemId, String op) {
        try {
            return embedBatch(chunks.stream().map(ChunkResult::chunkText).toList());
        } catch (Exception e) {
            log.error("Embedding 失败: itemId={}, op={}", knowledgeItemId, op, e);
            throw new RuntimeException("Embedding API 调用失败", e);
        }
    }

    private void writeToRedisWithRetry(List<String> embeddingIds, List<float[]> embeddings) {
        writeChunkVectorsToRedis(redisTemplate, embeddingIds, embeddings);
    }

    /**
     * 按统一格式批量写 chunk 向量到 Redis（hash：embedding + chunk_id），3 次重试。
     * 持久化（afterCommit）与 DataConsistencyTask 补偿共用，杜绝格式漂移。
     */
    public static void writeChunkVectorsToRedis(StringRedisTemplate redisTemplate,
                                                List<String> embeddingIds, List<float[]> embeddings) {
        for (int retry = 0; retry < MAX_RETRIES; retry++) {
            try {
                redisTemplate.executePipelined((org.springframework.data.redis.connection.RedisConnection connection) -> {
                    for (int i = 0; i < embeddingIds.size(); i++) {
                        String key = RedisKeyConstants.DART_PREFIX + embeddingIds.get(i);
                        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                        byte[] vectorBytes = com.dart.util.VectorUtils.floatArrayToBytes(embeddings.get(i));
                        connection.hashCommands().hSet(keyBytes,
                                "embedding".getBytes(StandardCharsets.UTF_8), vectorBytes);
                        connection.hashCommands().hSet(keyBytes,
                                "chunk_id".getBytes(StandardCharsets.UTF_8),
                                embeddingIds.get(i).getBytes(StandardCharsets.UTF_8));
                    }
                    return null;
                });
                log.debug("Redis Pipeline 批量写入成功: {} 条", embeddingIds.size());
                return;
            } catch (Exception e) {
                log.warn("Redis Pipeline 写入失败 (重试 {}/{}): 共 {} 条", retry + 1, MAX_RETRIES, embeddingIds.size(), e);
                if (retry < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(RETRY_DELAYS_MS[retry]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
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
