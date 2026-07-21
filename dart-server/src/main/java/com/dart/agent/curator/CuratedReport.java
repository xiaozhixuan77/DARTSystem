package com.dart.agent.curator;

import java.time.Instant;
import java.util.List;

/**
 * Curator Agent 生成的整理报告值对象。
 */
public record CuratedReport(String title, String markdown, List<String> sources, Instant generatedAt) {
}
