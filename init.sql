-- ============================================================
-- DART 数据库初始化脚本
-- 版本: 1.0
-- 数据库: MySQL 8.0 InnoDB
-- 字符集: utf8mb4 / utf8mb4_unicode_ci
-- ============================================================

-- 0. 创建数据库用户（最小权限）
CREATE USER IF NOT EXISTS 'dart_app'@'%' IDENTIFIED BY 'dart_app_2024';
GRANT SELECT, INSERT, UPDATE, DELETE ON dart.* TO 'dart_app'@'%';
FLUSH PRIVILEGES;

USE dart;

-- ============================================================
-- 1. user — 用户表
-- ============================================================
CREATE TABLE IF NOT EXISTS `user` (
    `id`            BIGINT          NOT NULL AUTO_INCREMENT,
    `username`      VARCHAR(50)     NOT NULL,
    `password_hash` VARCHAR(255)    NOT NULL COMMENT 'bcrypt $2a$12$...',
    `email`         VARCHAR(200)    DEFAULT NULL COMMENT '预留，非必填',
    `status`        TINYINT         NOT NULL DEFAULT 0 COMMENT '0=正常, 1=锁定, 2=禁用',
    `login_attempts` INT            NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
    `locked_until`  DATETIME        DEFAULT NULL COMMENT '锁定截止时间，NULL=未锁定',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

-- ============================================================
-- 2. refresh_token — JWT 刷新令牌表
-- ============================================================
CREATE TABLE IF NOT EXISTS `refresh_token` (
    `id`            BIGINT          NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT          NOT NULL,
    `token_hash`    VARCHAR(128)    NOT NULL COMMENT 'SHA-256(token原文)',
    `device_info`   VARCHAR(500)    DEFAULT NULL COMMENT 'User-Agent摘要',
    `expires_at`    DATETIME        NOT NULL,
    `revoked`       TINYINT         NOT NULL DEFAULT 0 COMMENT '0=有效, 1=已撤销',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_token_hash` (`token_hash`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_user_revoked` (`user_id`, `revoked`),
    KEY `idx_expires` (`expires_at`),
    CONSTRAINT `fk_rt_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

-- ============================================================
-- 3. conversation — 会话表
-- ============================================================
CREATE TABLE IF NOT EXISTS `conversation` (
    `id`            BIGINT          NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT          NOT NULL,
    `title`         VARCHAR(200)    NOT NULL DEFAULT '新对话',
    `tags`          VARCHAR(500)    DEFAULT NULL COMMENT 'JSON数组 ["tag1","tag2"]',
    `summary`       TEXT            DEFAULT NULL COMMENT '会话滚动摘要（增量压缩，仅报告主体）',
    `is_deleted`    TINYINT         NOT NULL DEFAULT 0 COMMENT '0=正常, 1=已删除',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_deleted_updated` (`user_id`, `is_deleted`, `updated_at` DESC),
    CONSTRAINT `fk_conv_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

-- ============================================================
-- 4. message — 消息表
-- ============================================================
CREATE TABLE IF NOT EXISTS `message` (
    `id`                BIGINT          NOT NULL AUTO_INCREMENT,
    `conversation_id`   BIGINT          NOT NULL,
    `role`              VARCHAR(20)     NOT NULL COMMENT 'user / assistant / system',
    `content`           LONGTEXT        COMMENT 'Markdown内容',
    `metadata`          JSON            DEFAULT NULL COMMENT '评分数据、来源URL、Agent过程日志',
    `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_conv_created` (`conversation_id`, `created_at`),
    CONSTRAINT `fk_msg_conv` FOREIGN KEY (`conversation_id`) REFERENCES `conversation` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

-- ============================================================
-- 5. knowledge_item — 知识条目表
-- ============================================================
CREATE TABLE IF NOT EXISTS `knowledge_item` (
    `id`                BIGINT          NOT NULL AUTO_INCREMENT,
    `user_id`           BIGINT          NOT NULL,
    `title`             VARCHAR(500)    NOT NULL,
    `content`           LONGTEXT        COMMENT 'Markdown正文（Curator Agent生成的报告全文）',
    `source_url`        VARCHAR(2000)   DEFAULT NULL,
    `source_url_hash`   CHAR(64)        DEFAULT NULL COMMENT 'SHA-256(source_url)，用于去重',
    `tags`              VARCHAR(500)    DEFAULT NULL COMMENT 'JSON数组',
    `score_t`           INT             DEFAULT NULL COMMENT '时效性 1-5',
    `score_a`           INT             DEFAULT NULL COMMENT '权威性 1-5',
    `score_d`           INT             DEFAULT NULL COMMENT '深度 1-5',
    `score_m`           INT             DEFAULT NULL COMMENT '匹配度 1-5',
    `weighted_score`    DECIMAL(4,2)    DEFAULT NULL COMMENT '加权总分 S=0.15T+0.30A+0.20D+0.35M',
    `notes`             TEXT            DEFAULT NULL COMMENT '用户个人笔记',
    `is_deleted`        TINYINT         NOT NULL DEFAULT 0 COMMENT '0=正常, 1=已删除',
    `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_deleted_created` (`user_id`, `is_deleted`, `created_at` DESC),
    KEY `idx_user_url_hash` (`user_id`, `source_url_hash`) COMMENT '同用户去重检测',
    FULLTEXT KEY `ft_title_content` (`title`, `content`),
    CONSTRAINT `fk_ki_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

-- ============================================================
-- 6. knowledge_chunk — 知识分块表
-- ============================================================
CREATE TABLE IF NOT EXISTS `knowledge_chunk` (
    `id`                BIGINT          NOT NULL AUTO_INCREMENT,
    `knowledge_item_id` BIGINT          NOT NULL,
    `chunk_index`       INT             NOT NULL COMMENT '分块序号，从0开始',
    `chunk_text`        TEXT            NOT NULL COMMENT '500字左右的分块文本',
    `embedding_id`      VARCHAR(200)    NOT NULL COMMENT 'Redis向量Key: chunk:{itemId}_{chunkIndex}',
    `embedding`         MEDIUMTEXT      NULL     COMMENT 'Base64编码的1024维向量',
    `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_embedding_id` (`embedding_id`),
    KEY `idx_item_id` (`knowledge_item_id`),
    CONSTRAINT `fk_kc_item` FOREIGN KEY (`knowledge_item_id`) REFERENCES `knowledge_item` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

-- ============================================================
-- 7. audit_log — 审计日志表
-- ============================================================
CREATE TABLE IF NOT EXISTS `audit_log` (
    `id`            BIGINT          NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT          DEFAULT NULL COMMENT 'NULL=匿名操作',
    `action`        VARCHAR(50)     NOT NULL COMMENT 'LOGIN / LOGOUT / SEARCH / CREATE_KNOWLEDGE / DELETE_KNOWLEDGE / ...',
    `resource_type` VARCHAR(50)     DEFAULT NULL COMMENT 'user / conversation / knowledge_item',
    `resource_id`   BIGINT          DEFAULT NULL COMMENT '操作对象ID',
    `details`       JSON            DEFAULT NULL COMMENT '补充信息：IP、User-Agent、变更摘要',
    `ip_address`    VARCHAR(45)     DEFAULT NULL COMMENT 'IPv4/IPv6',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_action_time` (`user_id`, `action`, `created_at` DESC),
    KEY `idx_created_at` (`created_at`),
    CONSTRAINT `fk_al_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
        ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

-- ============================================================
-- 8. search_trace — 搜索链路追踪表
-- ============================================================
CREATE TABLE IF NOT EXISTS `search_trace` (
    `id`                 BIGINT          NOT NULL AUTO_INCREMENT,
    `user_id`            BIGINT          NOT NULL COMMENT '用户ID',
    `conversation_id`    BIGINT          NOT NULL COMMENT '会话ID',
    `message_id`         BIGINT          NOT NULL COMMENT '关联的 Message.id（user 消息）',
    `query`              VARCHAR(2000)   NOT NULL COMMENT '原始查询',
    `intent`             VARCHAR(50)     DEFAULT NULL COMMENT '查询意图分类结果',
    `rag_hit`            TINYINT(1)      NOT NULL DEFAULT 0 COMMENT 'RAG是否命中',
    `rag_score`          DOUBLE          DEFAULT NULL COMMENT 'RAG最大相似度得分',
    `search_keywords`    VARCHAR(1000)   DEFAULT NULL COMMENT '实际使用的搜索关键词',
    `total_results`      INT             NOT NULL DEFAULT 0 COMMENT '搜索结果总数（评分后）',
    `filtered_results`   INT             NOT NULL DEFAULT 0 COMMENT '评分筛选后保留数',
    `top_scores_json`    TEXT            DEFAULT NULL COMMENT 'Top-5 结果评分明细JSON',
    `curation_type`      VARCHAR(20)     NOT NULL COMMENT '报告类型: rag/search/error',
    `fallback_reason`    VARCHAR(500)    DEFAULT NULL COMMENT '触发降级的原因',
    `processing_time_ms` INT             DEFAULT NULL COMMENT '总耗时（ms）',
    `created_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_created` (`user_id`, `created_at` DESC),
    KEY `idx_conv` (`conversation_id`),
    CONSTRAINT `fk_st_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

-- ============================================================
-- 9. knowledge_relation — 知识关系图谱表
-- ============================================================
CREATE TABLE IF NOT EXISTS `knowledge_relation` (
    `id`            BIGINT          NOT NULL AUTO_INCREMENT,
    `subject_id`    BIGINT          NOT NULL COMMENT '主体知识条目ID（knowledge_item.id）',
    `predicate`     VARCHAR(50)     NOT NULL COMMENT '关系类型: related_to/prerequisite_of/extends/part_of/alternative_to/depends_on',
    `object_id`     BIGINT          DEFAULT NULL COMMENT '客体知识条目ID（知识库内匹配）',
    `object_label`  VARCHAR(500)    DEFAULT NULL COMMENT '客体标签（知识库外的外部概念）',
    `confidence`    DOUBLE          DEFAULT 1.0 COMMENT '置信度 [0,1]',
    `source`        VARCHAR(500)    DEFAULT NULL COMMENT '关系来源（URL 或 LLM抽取）',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_subject` (`subject_id`),
    KEY `idx_object` (`object_id`),
    KEY `idx_predicate` (`predicate`),
    CONSTRAINT `fk_kr_subject` FOREIGN KEY (`subject_id`) REFERENCES `knowledge_item` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

-- ============================================================
-- 10. knowledge_entity — 知识实体表（topic 白名单主题 / concept 自由概念）
-- ============================================================
CREATE TABLE IF NOT EXISTS `knowledge_entity` (
    `id`            BIGINT          NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT          NOT NULL COMMENT '所属用户（user.id）',
    `name`          VARCHAR(100)    NOT NULL COMMENT '实体名（Redis/缓存淘汰策略…）',
    `type`          VARCHAR(20)     NOT NULL COMMENT 'topic=白名单主题 / concept=自由概念',
    `description`   VARCHAR(500)    DEFAULT NULL COMMENT 'LLM 一句话说明',
    `embedding`     TEXT            DEFAULT NULL COMMENT 'Base64编码的1024维向量（concept 语义）',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_name` (`user_id`, `name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;

-- ============================================================
-- 11. knowledge_item_entity — 知识条目-实体关联表
-- ============================================================
CREATE TABLE IF NOT EXISTS `knowledge_item_entity` (
    `id`            BIGINT          NOT NULL AUTO_INCREMENT,
    `item_id`       BIGINT          NOT NULL COMMENT 'knowledge_item.id',
    `entity_id`     BIGINT          NOT NULL COMMENT 'knowledge_entity.id',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_item_entity` (`item_id`, `entity_id`),
    KEY `idx_entity` (`entity_id`),
    CONSTRAINT `fk_ie_item` FOREIGN KEY (`item_id`) REFERENCES `knowledge_item` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;
