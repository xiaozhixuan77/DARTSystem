package com.dart.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dart.agent.scoring.ScoredResource;
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

    /** 实体通道 chunk 相关度下限：实体命中的条目仍需 chunk 达到此值才入池（防泛词实体免阈值灌入）。
     *  字段默认值保证测试直构建（非 Spring 注入）时生效。 */
    @Value("${agent.rag.entity-chunk-threshold:0.45}")
    private double entityChunkThreshold = 0.45;

    /**
     * RAG 命中结果：携带知识库命中材料（转 ScoredResource，供 Curator 生成完整报告）。
     * weakHit=材料不足（最高相似度低于强匹配阈值且非强实体命中），ChatService 据此降级搜索。
     */
    public record RagResult(double maxSimilarity, boolean weakHit, List<ScoredResource> materials) {
        public List<String> sourceUrls() {
            return materials.stream().map(ScoredResource::getUrl).distinct().toList();
        }
        /** 材料摘要拼接（仅评估框架等离线消费；在线输出由 Curator 生成完整报告）。 */
        public String answer() {
            return materials.stream()
                    .map(r -> "标题: " + r.getTitle() + "\n内容: "
                            + (r.getContent().length() > 300
                            ? r.getContent().substring(0, 300) : r.getContent()))
                    .reduce("", (a, b) -> a + "\n\n" + b);
        }
    }
    public record RagChunk(String content, String title, String sourceUrl, double similarity) {}

    /** 强相关判定阈值：最高相似度低于此值时命中视为弱相关（材料不足），ChatService 降级搜索。 */
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
            // 实体命中条目：chunk 仍需 ≥ entityChunkThreshold（相对阈值，非完全免阈值，防泛词实体灌入）；
            // 实体命中且达正常阈值才算强实体命中（材料充分，无标记直接回答）
            List<RagChunk> matched = new ArrayList<>();
            boolean entityStrongHit = false;
            for (KnowledgeChunk c : chunks) {
                KnowledgeItem item = itemMap.get(c.getKnowledgeItemId());
                if (item == null || c.getEmbedding() == null) continue;
                float[] chunkVec = com.dart.util.VectorUtils.decodeFromBase64(c.getEmbedding());
                double similarity = com.dart.util.VectorUtils.cosineSimilarity(queryVec, chunkVec);
                boolean entityHit = entityHitItemIds.contains(c.getKnowledgeItemId());
                if (similarity >= similarityThreshold
                        || (entityHit && similarity >= entityChunkThreshold)) {
                    matched.add(new RagChunk(c.getChunkText(), item.getTitle(),
                            item.getSourceUrl(), similarity));
                    if (entityHit && similarity >= similarityThreshold) {
                        entityStrongHit = true;
                    }
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

            // 1-hop 关联知识并入材料（转"关联知识"聚合资源，Curator 完整报告可见）
            List<ScoredResource> materials = toMaterials(matched);
            List<String> relatedContext = loadRelatedContexts(itemMap);
            if (!relatedContext.isEmpty()) {
                materials = new ArrayList<>(materials);
                materials.add(new ScoredResource("knowledge://related", "关联知识",
                        String.join("\n\n", relatedContext), 0, 0, 0, 0, 0.0));
            }
            double maxSimilarity = matched.getFirst().similarity();
            boolean weakHit = maxSimilarity < STRONG_MATCH_THRESHOLD && !entityStrongHit;
            return Optional.of(new RagResult(maxSimilarity, weakHit, materials));

        } catch (Exception e) {
            log.warn("RAG 检索失败: {}", query, e);
            return Optional.empty();
        }
    }

    private List<ScoredResource> toMaterials(List<RagChunk> matched) {
        return matched.stream()
                .map(c -> new ScoredResource(c.sourceUrl(), c.title(), c.content(),
                        0, 0, 0, 0, c.similarity()))
                .toList();
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
