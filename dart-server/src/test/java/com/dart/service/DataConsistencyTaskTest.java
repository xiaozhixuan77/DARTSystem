package com.dart.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dart.entity.KnowledgeChunk;
import com.dart.mapper.KnowledgeChunkMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DataConsistencyTask 向量缺失检测：检查的 Redis key 格式必须与
 * EmbeddingService 写入格式（dart:chunk:<embeddingId>）一致，
 * 曾出现多拼一层 "chunk:" 前缀导致全部误报缺失。
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class DataConsistencyTaskTest {

    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private DataConsistencyTask dataConsistencyTask;

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachLogAppender() {
        logger = (Logger) LoggerFactory.getLogger(DataConsistencyTask.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachLogAppender() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("检查的 key 格式与 EmbeddingService 写入一致（dart:chunk:<embeddingId>）")
    void detectMissingVectors_checksSameKeyFormatAsEmbeddingServiceWrite() {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setEmbeddingId("chunk:1:0");
        Page<KnowledgeChunk> page = new Page<>(1, 500);
        page.setTotal(1);
        page.setRecords(List.of(chunk));
        when(knowledgeChunkMapper.selectPage(any(IPage.class), any(Wrapper.class)))
                .thenReturn(page);

        List<String> checkedKeys = new ArrayList<>();
        RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
        when(keyCommands.exists(any(byte[].class))).thenAnswer(inv2 -> {
            checkedKeys.add(new String((byte[]) inv2.getArgument(0), StandardCharsets.UTF_8));
            return true;
        });
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.keyCommands()).thenReturn(keyCommands);
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            callback.doInRedis(connection);
            return List.of(true);
        });

        dataConsistencyTask.detectMissingVectors();

        // 错误拼接会是 dart:chunk:chunk:1:0（多一层 chunk: 前缀，永远查不到真实 key）
        assertThat(checkedKeys).containsExactly("dart:chunk:1:0");
    }

    @Test
    @DisplayName("Redis 存在对应 key 时不误报缺失")
    void detectMissingVectors_noFalsePositiveWhenRedisKeyExists() {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setEmbeddingId("chunk:1:0");
        Page<KnowledgeChunk> page = new Page<>(1, 500);
        page.setTotal(1);
        page.setRecords(List.of(chunk));
        when(knowledgeChunkMapper.selectPage(any(IPage.class), any(Wrapper.class)))
                .thenReturn(page);

        RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
        when(keyCommands.exists(any(byte[].class))).thenReturn(true);
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.keyCommands()).thenReturn(keyCommands);
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            callback.doInRedis(connection);
            return List.of(true);
        });

        dataConsistencyTask.detectMissingVectors();

        List<String> messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).noneMatch(m -> m.contains("发现缺失向量"));
    }
}
