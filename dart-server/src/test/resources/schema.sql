-- 集成测试 MySQL schema（Testcontainers 自动执行）
-- 与 System/init.sql 保持同步

CREATE TABLE IF NOT EXISTS `user` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `username`        VARCHAR(50)  NOT NULL,
    `password_hash`   VARCHAR(255) NOT NULL COMMENT 'bcrypt',
    `email`           VARCHAR(200) DEFAULT NULL,
    `status`          TINYINT      NOT NULL DEFAULT 0 COMMENT '0=正常, 1=锁定, 2=禁用',
    `login_attempts`  INT          NOT NULL DEFAULT 0,
    `locked_until`    DATETIME     DEFAULT NULL,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `refresh_token` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT       NOT NULL,
    `token_hash`    VARCHAR(128) NOT NULL COMMENT 'SHA-256(token)',
    `device_info`   VARCHAR(500) DEFAULT NULL,
    `expires_at`    DATETIME     NOT NULL,
    `revoked`       TINYINT      NOT NULL DEFAULT 0 COMMENT '0=有效, 1=已撤销',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_token_hash` (`token_hash`),
    KEY `idx_user_id` (`user_id`),
    CONSTRAINT `fk_rt_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `conversation` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT       NOT NULL,
    `title`         VARCHAR(200) NOT NULL DEFAULT '新对话',
    `tags`          VARCHAR(500) DEFAULT NULL COMMENT 'JSON数组',
    `summary`       TEXT DEFAULT NULL COMMENT '会话滚动摘要',
    `is_deleted`    TINYINT      NOT NULL DEFAULT 0 COMMENT '0=正常, 1=已删除',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_deleted_updated` (`user_id`, `is_deleted`, `updated_at` DESC),
    CONSTRAINT `fk_conv_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `message` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `conversation_id`   BIGINT       NOT NULL,
    `role`              VARCHAR(20)  NOT NULL COMMENT 'user / assistant / system',
    `content`           LONGTEXT     COMMENT 'Markdown',
    `metadata`          JSON         DEFAULT NULL,
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_conv_created` (`conversation_id`, `created_at`),
    CONSTRAINT `fk_msg_conv` FOREIGN KEY (`conversation_id`) REFERENCES `conversation` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `knowledge_item` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`           BIGINT       NOT NULL,
    `title`             VARCHAR(500) NOT NULL,
    `content`           LONGTEXT     COMMENT 'Markdown正文',
    `source_url`        VARCHAR(2000) DEFAULT NULL,
    `source_url_hash`   CHAR(64)     DEFAULT NULL COMMENT 'SHA-256(source_url)',
    `tags`              VARCHAR(500) DEFAULT NULL,
    `score_t`           INT          DEFAULT NULL COMMENT '时效性 1-5',
    `score_a`           INT          DEFAULT NULL COMMENT '权威性 1-5',
    `score_d`           INT          DEFAULT NULL COMMENT '深度 1-5',
    `score_m`           INT          DEFAULT NULL COMMENT '匹配度 1-5',
    `weighted_score`    DECIMAL(4,2) DEFAULT NULL,
    `notes`             TEXT         DEFAULT NULL,
    `is_deleted`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0=正常, 1=已删除',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_deleted_created` (`user_id`, `is_deleted`, `created_at` DESC),
    CONSTRAINT `fk_ki_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `knowledge_chunk` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `knowledge_item_id` BIGINT       NOT NULL,
    `chunk_index`       INT          NOT NULL COMMENT '分块序号，从0开始',
    `chunk_text`        TEXT         NOT NULL,
    `embedding_id`      VARCHAR(200) NOT NULL COMMENT 'Redis向量Key',
    `embedding`         MEDIUMTEXT   NULL COMMENT 'Base64编码的1024维向量',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_embedding_id` (`embedding_id`),
    KEY `idx_item_id` (`knowledge_item_id`),
    CONSTRAINT `fk_kc_item` FOREIGN KEY (`knowledge_item_id`) REFERENCES `knowledge_item` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `audit_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT       DEFAULT NULL COMMENT 'NULL=匿名',
    `action`        VARCHAR(50)  NOT NULL,
    `resource_type` VARCHAR(50)  DEFAULT NULL,
    `resource_id`   BIGINT       DEFAULT NULL,
    `details`       JSON         DEFAULT NULL,
    `ip_address`    VARCHAR(45)  DEFAULT NULL,
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_action_time` (`user_id`, `action`, `created_at` DESC),
    CONSTRAINT `fk_al_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `search_trace` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`            BIGINT       NOT NULL COMMENT '用户ID',
    `conversation_id`    BIGINT       NOT NULL COMMENT '会话ID',
    `message_id`         BIGINT       NOT NULL COMMENT '关联的 Message.id（user 消息）',
    `query`              VARCHAR(2000) NOT NULL COMMENT '原始查询',
    `intent`             VARCHAR(50)  DEFAULT NULL COMMENT '查询意图分类结果',
    `rag_hit`            TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'RAG是否命中',
    `rag_score`          DOUBLE       DEFAULT NULL COMMENT 'RAG最大相似度得分',
    `search_keywords`    VARCHAR(1000) DEFAULT NULL COMMENT '实际使用的搜索关键词',
    `total_results`      INT          NOT NULL DEFAULT 0 COMMENT '搜索结果总数（评分后）',
    `filtered_results`   INT          NOT NULL DEFAULT 0 COMMENT '评分筛选后保留数',
    `top_scores_json`    TEXT         DEFAULT NULL COMMENT 'Top-5 结果评分明细JSON',
    `curation_type`      VARCHAR(20)  NOT NULL COMMENT '报告类型: rag/search/error',
    `fallback_reason`    VARCHAR(500) DEFAULT NULL COMMENT '触发降级的原因',
    `processing_time_ms` INT          DEFAULT NULL COMMENT '总耗时（ms）',
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_created` (`user_id`, `created_at` DESC),
    KEY `idx_conv` (`conversation_id`),
    CONSTRAINT `fk_st_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `knowledge_relation` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `subject_id`    BIGINT       NOT NULL COMMENT '主体知识条目ID（knowledge_item.id）',
    `predicate`     VARCHAR(50)  NOT NULL COMMENT '关系类型: related_to/prerequisite_of/extends/part_of/alternative_to/depends_on',
    `object_id`     BIGINT       DEFAULT NULL COMMENT '客体知识条目ID（知识库内匹配）',
    `object_label`  VARCHAR(500) DEFAULT NULL COMMENT '客体标签（知识库外的外部概念）',
    `confidence`    DOUBLE       DEFAULT 1.0 COMMENT '置信度 [0,1]',
    `source`        VARCHAR(500) DEFAULT NULL COMMENT '关系来源（URL 或 LLM抽取）',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_subject` (`subject_id`),
    KEY `idx_object` (`object_id`),
    KEY `idx_predicate` (`predicate`),
    CONSTRAINT `fk_kr_subject` FOREIGN KEY (`subject_id`) REFERENCES `knowledge_item` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `knowledge_entity` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT       NOT NULL,
    `name`          VARCHAR(100) NOT NULL,
    `type`          VARCHAR(20)  NOT NULL,
    `description`   VARCHAR(500) DEFAULT NULL,
    `embedding`     TEXT         DEFAULT NULL,
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_name` (`user_id`, `name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `knowledge_item_entity` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `item_id`       BIGINT       NOT NULL,
    `entity_id`     BIGINT       NOT NULL,
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_item_entity` (`item_id`, `entity_id`),
    KEY `idx_entity` (`entity_id`),
    CONSTRAINT `fk_ie_item` FOREIGN KEY (`item_id`) REFERENCES `knowledge_item` (`id`)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
