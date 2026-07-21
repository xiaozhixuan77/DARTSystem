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
    `embedding`         MEDIUMTEXT      NULL     COMMENT 'Base64编码的1536维向量',
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
