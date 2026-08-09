package com.dart.evaluation.model;

import java.util.List;

/**
 * 评估测试集条目：来源 evaluation-queries.json。
 */
public record TestQuery(String id, String domain, String query, String difficulty,
                        String expectedType, List<String> keyConcepts, int minSources) {
}
