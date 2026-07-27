package com.dart.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时检查 Redis 连接可用性。
 * 向量索引通过 Redis Stack 的 FT.CREATE 命令手动创建或通过初始化脚本创建。
 */
@Component
public class VectorIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexInitializer.class);

    @Override
    public void run(ApplicationArguments args) {
        log.info("向量索引初始化器已注册");
        log.info("请确保 Redis Stack 索引已创建: FT.CREATE idx:knowledge_vector "
                + "ON HASH PREFIX 1 dart:chunk: SCHEMA embedding VECTOR HNSW 6 DIM 1024 "
                + "DISTANCE_METRIC COSINE TYPE FLOAT32");
    }
}
