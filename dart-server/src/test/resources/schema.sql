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
    `embedding`         MEDIUMTEXT   NULL COMMENT 'Base64编码的1536维向量',
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
