package com.dart.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dart.entity.Conversation;
import com.dart.mapper.ConversationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 会话清理定时任务：每天凌晨清理空会话。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationCleanupTask {

    private final ConversationMapper conversationMapper;

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupEmptyConversations() {
        log.info("开始清理空会话...");
        try {
            QueryWrapper<Conversation> query = new QueryWrapper<>();
            query.eq("is_deleted", 0)
                    .apply("id NOT IN (SELECT DISTINCT conversation_id FROM message)");
            Long count = conversationMapper.selectCount(query);
            if (count > 0) {
                // 使用 delete(query) 而非 update(entity, query)：
                // MyBatis-Plus 全局逻辑删除会拦截 update 方法中的 is_deleted 字段设置
                conversationMapper.delete(query);
                log.info("清理空会话完成: {} 条", count);
            }
        } catch (Exception e) {
            log.error("会话清理异常", e);
        }
    }
}
