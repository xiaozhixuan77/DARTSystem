package com.dart.dto.response;

import java.util.List;

/**
 * 知识关系图数据（力导向图渲染用）。节点 id：知识条目 "item:{id}"，外部概念 "ext:{label}"。
 */
public record KnowledgeGraphDTO(List<GraphNode> nodes, List<GraphEdge> edges) {

    public record GraphNode(String id, String label, String category) {}

    public record GraphEdge(String source, String target, String label) {}
}
