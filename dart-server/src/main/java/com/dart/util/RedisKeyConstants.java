package com.dart.util;

/**
 * Redis Key 前缀常量。
 */
public final class RedisKeyConstants {

    private RedisKeyConstants() {}

    public static final String DART_PREFIX = "dart:";
    public static final String CHUNK_PREFIX = DART_PREFIX + "chunk:";
    public static final String REPORT_CACHE_PREFIX = DART_PREFIX + "report-cache:";
}
