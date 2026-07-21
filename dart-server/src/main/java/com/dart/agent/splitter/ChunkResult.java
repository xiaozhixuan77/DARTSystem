package com.dart.agent.splitter;

/**
 * 文本分块结果值对象。
 */
public record ChunkResult(String sectionTitle, String chunkText, int chunkIndex) {
}
