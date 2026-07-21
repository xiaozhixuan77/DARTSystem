package com.dart.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dart.entity.KnowledgeChunk;
import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.util.RedisKeyConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 数据一致性补偿任务：检测缺失的 Redis 向量，每日对账。
 */
@Component
public class DataConsistencyTask {

    private static final Logger log = LoggerFactory.getLogger(DataConsistencyTask.class);
    private static final int BATCH_SIZE = 500;
    private static final String REDIS_KEY_PREFIX = RedisKeyConstants.CHUNK_PREFIX;

    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final StringRedisTemplate redisTemplate;

    public DataConsistencyTask(KnowledgeChunkMapper knowledgeChunkMapper,
                               StringRedisTemplate redisTemplate) {
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 每 5 分钟分页检查缺失的向量（仅记录，实际补偿在 ChatService 持久化时完成）。
     */
    @Scheduled(fixedDelay = 300_000)
    public void detectMissingVectors() {
        log.debug("开始补偿缺失向量...");
        try {
            int compensated = 0;
            int pageNum = 1;
            while (true) {
                Page<KnowledgeChunk> page = new Page<>(pageNum, BATCH_SIZE);
                QueryWrapper<KnowledgeChunk> wrapper = new QueryWrapper<>();
                wrapper.isNotNull("embedding_id");
                var result = knowledgeChunkMapper.selectPage(page, wrapper);
                if (result.getRecords().isEmpty()) break;

                for (KnowledgeChunk chunk : result.getRecords()) {
                    String key = REDIS_KEY_PREFIX + chunk.getEmbeddingId();
                    Boolean exists = redisTemplate.hasKey(key);
                    if (!Boolean.TRUE.equals(exists)) {
                        log.info("发现缺失向量: {}", chunk.getEmbeddingId());
                        compensated++;
                    }
                }
                if (!result.hasNext()) break;
                pageNum++;
            }
            if (compensated > 0) {
                log.info("向量补偿完成: 发现 {} 条缺失", compensated);
            }
        } catch (Exception e) {
            log.error("向量补偿任务异常", e);
        }
    }

    /**
     * 每天凌晨 4:13 对账（使用 SCAN 代替 KEYS）。
     */
    @Scheduled(cron = "0 13 4 * * ?")
    public void consistencyCheck() {
        log.info("开始每日向量对账...");
        try {
            long mysqlCount = knowledgeChunkMapper.selectCount(
                    new QueryWrapper<KnowledgeChunk>().isNotNull("embedding_id"));
            long redisCount = 0;
            try (Cursor<String> cursor = redisTemplate.scan(
                    ScanOptions.scanOptions().match(REDIS_KEY_PREFIX + "*").count(100).build())) {
                while (cursor.hasNext()) {
                    cursor.next();
                    redisCount++;
                }
            } catch (Exception e) {
                log.warn("Redis 统计失败", e);
            }

            if (mysqlCount > 0) {
                double deviation = Math.abs((double) (mysqlCount - redisCount) / mysqlCount);
                log.info("每日对账: MySQL={}, Redis={}, 偏差={}%", mysqlCount, redisCount,
                        String.format("%.1f", deviation * 100));
                if (deviation > 0.05) {
                    log.warn("向量偏差超过 5%，建议全量重建");
                }
            }
        } catch (Exception e) {
            log.error("对账任务异常", e);
        }
    }
}
