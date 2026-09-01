package com.dart.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dart.entity.KnowledgeChunk;
import com.dart.entity.KnowledgeItem;
import com.dart.entity.KnowledgeRelation;
import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.mapper.KnowledgeItemMapper;
import com.dart.mapper.KnowledgeRelationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * RAG 向量检索与回答生成。
 */
@Slf4j
@Service
public class RagService {

    private final EmbeddingService embeddingService;
    private final ChatModel chatModel;
    private final KnowledgeItemMapper knowledgeItemMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeRelationMapper relationMapper;
    private final EntityService entityService;
    private final double similarityThreshold;
    private final int topK;

    public RagService(EmbeddingService embeddingService, ChatModel chatModel,
                      KnowledgeItemMapper knowledgeItemMapper,
                      KnowledgeChunkMapper knowledgeChunkMapper,
                      KnowledgeRelationMapper relationMapper,
                      EntityService entityService,
                      @Value("${agent.rag.similarity-threshold:0.80}") double similarityThreshold,
                      @Value("${agent.rag.top-k:5}") int topK) {
        this.embeddingService = embeddingService;
        this.chatModel = chatModel;
        this.knowledgeItemMapper = knowledgeItemMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.relationMapper = relationMapper;
        this.entityService = entityService;
        this.similarityThreshold = similarityThreshold;
        this.topK = topK;
    }

    public record RagResult(String answer, List<String> sourceUrls, double maxSimilarity) {
        public RagResult(String answer, List<String> sourceUrls) {
            this(answer, sourceUrls, -1.0);
        }
    }
    public record RagChunk(String content, String title, String sourceUrl, double similarity) {}

    /** 弱相关命中时 LLM 被指示输出的"材料不足"标记；ChatService 检测到即降级搜索。 */
    public static final String RAG_INSUFFICIENT_MARKER = "RAG_INSUFFICIENT";

    /** 强相关判定阈值：最高相似度低于此值时命中视为弱相关，要求 LLM 用标记报告材料不足。 */
    private static final double STRONG_MATCH_THRESHOLD = 0.75;

    /**
     * RAG 检索：向量化查询 → 查本地知识库 → 命中则 LLM 生成回答。
     */
    public Optional<RagResult> retrieve(String query, Long userId) {
        try {
            float[] queryVec = embeddingService.embed(query);

            // 先查询用户的知识条目 ID，避免 SQL 注入
            QueryWrapper<KnowledgeItem> itemQuery = new QueryWrapper<>();
            itemQuery.select("id").eq("user_id", userId).eq("is_deleted", 0);
            List<Long> userItemIds = knowledgeItemMapper.selectList(itemQuery)
                    .stream().map(KnowledgeItem::getId).toList();

            if (userItemIds.isEmpty()) {
                return Optional.empty();
            }

            // 全量拉取用户 chunk（无 SQL 截断）：无排序 LIMIT 会漏掉后沉淀条目的相似块；
            // 量级 ~600 块内存余弦毫秒级，匹配结果在内存排序后按 topK 截断
            QueryWrapper<KnowledgeChunk> wrapper = new QueryWrapper<>();
            wrapper.in("knowledge_item_id", userItemIds);
            List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectList(wrapper);

            if (chunks.isEmpty()) {
                return Optional.empty();
            }

            // 批量加载 KnowledgeItem，避免 N+1 查询
            Set<Long> itemIds = new HashSet<>();
            for (KnowledgeChunk c : chunks) {
                if (c.getKnowledgeItemId() != null) {
                    itemIds.add(c.getKnowledgeItemId());
                }
            }
            Map<Long, KnowledgeItem> itemMap = new HashMap<>();
            for (KnowledgeItem item : knowledgeItemMapper.selectBatchIds(itemIds)) {
                if (item != null && userId.equals(item.getUserId()) && item.getIsDeleted() == 0) {
                    itemMap.put(item.getId(), item);
                }
            }

            // 实体通道：实体名精确匹配 + 概念向量匹配 → 命中条目的 chunk 免阈值入池
            // （实体命中是强信号，防"概念命中但段落表述不同"漏检；topK 截断兜底）
            Set<Long> entityHitItemIds = new HashSet<>(entityService.findItemsByEntityName(userId, query));
            entityHitItemIds.addAll(entityService.matchEntitiesByVector(userId, queryVec));

            // 用 MySQL 中存储的向量计算余弦相似度（避免重复 Embedding API 调用）
            List<RagChunk> matched = new ArrayList<>();
            for (KnowledgeChunk c : chunks) {
                KnowledgeItem item = itemMap.get(c.getKnowledgeItemId());
                if (item == null || c.getEmbedding() == null) continue;
                float[] chunkVec = com.dart.util.VectorUtils.bytesToFloatArray(
                        java.util.Base64.getDecoder().decode(c.getEmbedding()));
                double similarity = com.dart.util.VectorUtils.cosineSimilarity(queryVec, chunkVec);
                if (similarity >= similarityThreshold
                        || entityHitItemIds.contains(c.getKnowledgeItemId())) {
                    matched.add(new RagChunk(c.getChunkText(), item.getTitle(),
                            item.getSourceUrl(), similarity));
                }
            }

            if (matched.isEmpty()) {
                return Optional.empty();
            }

            // 按相似度降序排列，最多采用 topK 个匹配块生成回答
            matched.sort(Comparator.comparingDouble(RagChunk::similarity).reversed());
            if (matched.size() > topK) {
                matched = matched.subList(0, topK);
            }

            // 1-hop 知识图谱扩展：命中条目的关联知识拼入生成上下文
            List<String> relatedContext = loadRelatedContexts(itemMap);
            String answer = generateAnswer(query, matched, relatedContext, !entityHitItemIds.isEmpty());
            List<String> sources = matched.stream().map(RagChunk::sourceUrl).distinct().toList();
            return Optional.of(new RagResult(answer, sources, matched.getFirst().similarity()));

        } catch (Exception e) {
            log.warn("RAG 检索失败: {}", query, e);
            return Optional.empty();
        }
    }

    public String generateAnswer(String query, List<RagChunk> chunks, List<String> relatedContext,
                                 boolean strongEntityHit) {
        StringBuilder context = new StringBuilder();
        for (RagChunk c : chunks) {
            context.append("<source>\n");
            context.append("标题: ").append(c.title()).append("\n");
            context.append("来源: ").append(c.sourceUrl()).append("\n");
            context.append("内容: ").append(c.content()).append("\n");
            context.append("</source>\n\n");
        }
        if (relatedContext != null && !relatedContext.isEmpty()) {
            context.append("<related>\n");
            for (String r : relatedContext) {
                context.append(r).append("\n");
            }
            context.append("</related>\n\n");
        }

        double maxSimilarity = chunks.stream()
                .mapToDouble(RagChunk::similarity).max().orElse(0.0);
        // 实体命中是强信号（查询词含实体名 = 明确意图），即使相似度擦线也不判"材料不足"
        String insufficiencyInstruction = (maxSimilarity < STRONG_MATCH_THRESHOLD && !strongEntityHit)
                ? "如果参考材料不足以完整回答用户问题，请只输出 " + RAG_INSUFFICIENT_MARKER
                + "（不要输出其他任何内容）。"
                : "如果参考材料不足以回答问题，请如实说明。";
        String prompt = """
                你是一个技术问答助手。请基于以下参考材料回答用户问题。
                %s 若存在关联知识补充上下文（related 标签块），可作为额外参考。

                用户问题：%s

                参考材料：
                %s

                请根据参考材料生成结构化中文回答：
                - 先给出 1-2 句直接结论
                - 再按要点分点展开（- 列表），覆盖材料中的概念、步骤、示例等关键信息
                - 材料含代码时保留关键代码片段
                - 回答总长度控制在 300-500 字，准确完整，不要省略核心技术点""".formatted(insufficiencyInstruction,
                query.replace("<", "&lt;"), context);

        try {
            return chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResults().getFirst().getOutput().getText().trim();
        } catch (Exception e) {
            log.warn("LLM 回答生成失败", e);
            return "# " + query + "\n\n基于知识库的搜索结果：\n\n"
                    + chunks.stream()
                    .map(c -> "- [" + c.title() + "](" + c.sourceUrl() + ")")
                    .reduce("", (a, b) -> a + "\n" + b);
        }
    }

    /**
     * 1-hop 图扩展：查命中条目关联的关系，取另一端知识条目（标题+摘要截断）作为补充上下文。
     */
    private List<String> loadRelatedContexts(Map<Long, KnowledgeItem> hitItems) {
        if (hitItems.isEmpty()) {
            return List.of();
        }
        List<KnowledgeRelation> relations;
        try {
            QueryWrapper<KnowledgeRelation> wrapper = new QueryWrapper<>();
            wrapper.in("subject_id", hitItems.keySet()).or().in("object_id", hitItems.keySet());
            relations = relationMapper.selectList(wrapper);
        } catch (Exception e) {
            log.warn("关系查询失败，跳过 1-hop 增强", e);
            return List.of();
        }
        if (relations.isEmpty()) {
            return List.of();
        }
        Set<Long> relatedIds = new HashSet<>();
        for (KnowledgeRelation r : relations) {
            if (r.getSubjectId() != null && !hitItems.containsKey(r.getSubjectId())) {
                relatedIds.add(r.getSubjectId());
            }
            if (r.getObjectId() != null && !hitItems.containsKey(r.getObjectId())) {
                relatedIds.add(r.getObjectId());
            }
        }
        Map<Long, KnowledgeItem> fetched = new HashMap<>();
        List<Long> missingIds = relatedIds.stream().filter(id -> !hitItems.containsKey(id)).toList();
        if (!missingIds.isEmpty()) {
            try {
                for (KnowledgeItem item : knowledgeItemMapper.selectBatchIds(missingIds)) {
                    if (item != null) {
                        fetched.put(item.getId(), item);
                    }
                }
            } catch (Exception e) {
                // 批量加载失败时跳过未命中条目（与原逐条容错语义一致）
            }
        }
        List<String> context = new ArrayList<>();
        for (Long id : relatedIds) {
            KnowledgeItem item = hitItems.get(id);
            if (item == null) {
                item = fetched.get(id);
            }
            if (item == null || item.getTitle() == null) {
                continue;
            }
            String content = item.getContent();
            String summary = content == null || content.length() <= 150
                    ? content : content.substring(0, 150) + "...";
            context.add(item.getTitle() + " | " + (summary == null ? "" : summary));
        }
        return context;
    }

}
