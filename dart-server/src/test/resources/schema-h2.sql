-- H2 兼容 schema（Mapper 单元测试用）
-- 与 MySQL schema 保持结构一致，去除 H2 不支持的语法

SET MODE MySQL;

CREATE TABLE IF NOT EXISTS `user` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `username`        VARCHAR(50)  NOT NULL,
    `password_hash`   VARCHAR(255) NOT NULL,
    `email`           VARCHAR(200) DEFAULT NULL,
    `status`          TINYINT      NOT NULL DEFAULT 0,
    `login_attempts`  INT          NOT NULL DEFAULT 0,
    `locked_until`    TIMESTAMP    DEFAULT NULL,
    `created_at`      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
);

CREATE TABLE IF NOT EXISTS `refresh_token` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT       NOT NULL,
    `token_hash`    VARCHAR(128) NOT NULL,
    `device_info`   VARCHAR(500) DEFAULT NULL,
    `expires_at`    TIMESTAMP    NOT NULL,
    `revoked`       TINYINT      NOT NULL DEFAULT 0,
    `created_at`    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_token_hash` (`token_hash`),
    KEY `idx_user_id` (`user_id`),
    CONSTRAINT `fk_rt_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
);

CREATE TABLE IF NOT EXISTS `conversation` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT       NOT NULL,
    `title`         VARCHAR(200) NOT NULL DEFAULT '新对话',
    `tags`          VARCHAR(500) DEFAULT NULL,
    `summary`       TEXT DEFAULT NULL,
    `is_deleted`    TINYINT      NOT NULL DEFAULT 0,
    `created_at`    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_deleted_updated` (`user_id`, `is_deleted`, `updated_at`),
    CONSTRAINT `fk_conv_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
);

CREATE TABLE IF NOT EXISTS `message` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `conversation_id`   BIGINT       NOT NULL,
    `role`              VARCHAR(20)  NOT NULL,
    `content`           CLOB,
    `metadata`          CLOB         DEFAULT NULL,
    `created_at`        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_conv_created` (`conversation_id`, `created_at`),
    CONSTRAINT `fk_msg_conv` FOREIGN KEY (`conversation_id`) REFERENCES `conversation` (`id`)
);

CREATE TABLE IF NOT EXISTS `knowledge_item` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`           BIGINT       NOT NULL,
    `title`             VARCHAR(500) NOT NULL,
    `content`           CLOB,
    `source_url`        VARCHAR(2000) DEFAULT NULL,
    `source_url_hash`   CHAR(64)     DEFAULT NULL,
    `tags`              VARCHAR(500) DEFAULT NULL,
    `score_t`           INT          DEFAULT NULL,
    `score_a`           INT          DEFAULT NULL,
    `score_d`           INT          DEFAULT NULL,
    `score_m`           INT          DEFAULT NULL,
    `weighted_score`    DECIMAL(4,2) DEFAULT NULL,
    `notes`             CLOB         DEFAULT NULL,
    `is_deleted`        TINYINT      NOT NULL DEFAULT 0,
    `created_at`        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_deleted_created` (`user_id`, `is_deleted`, `created_at`),
    CONSTRAINT `fk_ki_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
);

CREATE TABLE IF NOT EXISTS `knowledge_chunk` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `knowledge_item_id` BIGINT       NOT NULL,
    `chunk_index`       INT          NOT NULL,
    `chunk_text`        CLOB         NOT NULL,
    `embedding_id`      VARCHAR(200) NOT NULL,
    `embedding`         CLOB         DEFAULT NULL,
    `created_at`        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_embedding_id` (`embedding_id`),
    KEY `idx_item_id` (`knowledge_item_id`),
    CONSTRAINT `fk_kc_item` FOREIGN KEY (`knowledge_item_id`) REFERENCES `knowledge_item` (`id`)
);

CREATE TABLE IF NOT EXISTS `audit_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT       DEFAULT NULL,
    `action`        VARCHAR(50)  NOT NULL,
    `resource_type` VARCHAR(50)  DEFAULT NULL,
    `resource_id`   BIGINT       DEFAULT NULL,
    `details`       CLOB         DEFAULT NULL,
    `ip_address`    VARCHAR(45)  DEFAULT NULL,
    `created_at`    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_action_time` (`user_id`, `action`, `created_at`),
    CONSTRAINT `fk_al_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS `search_trace` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`            BIGINT       NOT NULL,
    `conversation_id`    BIGINT       NOT NULL,
    `message_id`         BIGINT       NOT NULL,
    `query`              VARCHAR(2000) NOT NULL,
    `intent`             VARCHAR(50)  DEFAULT NULL,
    `rag_hit`            TINYINT      NOT NULL DEFAULT 0,
    `rag_score`          DOUBLE       DEFAULT NULL,
    `search_keywords`    VARCHAR(1000) DEFAULT NULL,
    `total_results`      INT          NOT NULL DEFAULT 0,
    `filtered_results`   INT          NOT NULL DEFAULT 0,
    `top_scores_json`    CLOB         DEFAULT NULL,
    `curation_type`      VARCHAR(20)  NOT NULL,
    `fallback_reason`    VARCHAR(500) DEFAULT NULL,
    `processing_time_ms` INT          DEFAULT NULL,
    `created_at`         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_created` (`user_id`, `created_at`),
    KEY `idx_conv` (`conversation_id`),
    CONSTRAINT `fk_st_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS `knowledge_relation` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `subject_id`    BIGINT       NOT NULL,
    `predicate`     VARCHAR(50)  NOT NULL,
    `object_id`     BIGINT       DEFAULT NULL,
    `object_label`  VARCHAR(500) DEFAULT NULL,
    `confidence`    DOUBLE       DEFAULT 1.0,
    `source`        VARCHAR(500) DEFAULT NULL,
    `created_at`    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_subject` (`subject_id`),
    KEY `idx_object` (`object_id`),
    KEY `idx_predicate` (`predicate`),
    CONSTRAINT `fk_kr_subject` FOREIGN KEY (`subject_id`) REFERENCES `knowledge_item` (`id`) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS `knowledge_entity` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT       NOT NULL,
    `name`          VARCHAR(100) NOT NULL,
    `type`          VARCHAR(20)  NOT NULL,
    `description`   VARCHAR(500) DEFAULT NULL,
    `embedding`     TEXT         DEFAULT NULL,
    `created_at`    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_name` (`user_id`, `name`)
);

CREATE TABLE IF NOT EXISTS `knowledge_item_entity` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `item_id`       BIGINT       NOT NULL,
    `entity_id`     BIGINT       NOT NULL,
    `created_at`    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_item_entity` (`item_id`, `entity_id`),
    CONSTRAINT `fk_ie_item` FOREIGN KEY (`item_id`) REFERENCES `knowledge_item` (`id`) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS `app_api_config` (
    `id`            BIGINT       NOT NULL,
    `chat_base_url` VARCHAR(300) NOT NULL,
    `chat_model`    VARCHAR(100) NOT NULL,
    `chat_api_key`  VARCHAR(300) NOT NULL,
    `updated_at`    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);
