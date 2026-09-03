package com.dart.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dart.entity.KnowledgeEntity;
import com.dart.entity.KnowledgeItemEntity;
import com.dart.mapper.KnowledgeEntityMapper;
import com.dart.mapper.KnowledgeItemEntityMapper;
import com.dart.util.VectorUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识实体持久化与检索辅助：实体 upsert（同名聚合）、条目-实体关联（幂等）、
 * 实体列表计数、名称/向量匹配（RAG 实体通道）。
 */
@Slf4j
@Service
public class EntityService {

    public static final String TYPE_TOPIC = "topic";
    public static final String TYPE_CONCEPT = "concept";

    /** 实体向量匹配阈值：概念向量与查询向量余弦低于此值不视为命中（实测可调）。 */
    private static final double ENTITY_VECTOR_THRESHOLD = 0.75;
    /** 名称匹配最小实体名长度：防单字符（V/C）与 2 字符泛词（网络/网关/其他）误命中查询词。 */
    private static final int MIN_NAME_LEN = 3;
    /** 兜底主题（抽取白名单外的杂项分类）：无主题语义，禁止作为检索命中名。 */
    private static final String FALLBACK_TOPIC = "其他";

    private final KnowledgeEntityMapper entityMapper;
    private final KnowledgeItemEntityMapper itemEntityMapper;
    private final EmbeddingService embeddingService;

    public EntityService(KnowledgeEntityMapper entityMapper,
                         KnowledgeItemEntityMapper itemEntityMapper,
                         EmbeddingService embeddingService) {
        this.entityMapper = entityMapper;
        this.itemEntityMapper = itemEntityMapper;
        this.embeddingService = embeddingService;
    }

    /**
     * 实体 upsert：按 (user_id, name) 查重，缺失则 insert（concept 附向量）；返回全部实体 id。
     * topic 与 concept 顺序保持输入顺序。
     */
    public List<Long> upsertEntities(Long userId, EntityExtractionService.ExtractedEntities extracted) {
        if (extracted == null || extracted.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>(extracted.topics().size() + extracted.concepts().size());
        names.addAll(extracted.topics());
        names.addAll(extracted.concepts());

        Map<String, KnowledgeEntity> existing = new HashMap<>();
        for (KnowledgeEntity e : entityMapper.selectList(
                new QueryWrapper<KnowledgeEntity>().eq("user_id", userId))) {
            existing.put(normalizeName(e.getName()), e);
        }

        List<Long> ids = new ArrayList<>(names.size());
        for (String name : names) {
            KnowledgeEntity found = existing.get(normalizeName(name));
            if (found != null) {
                ids.add(found.getId());
                continue;
            }
            KnowledgeEntity entity = new KnowledgeEntity();
            entity.setUserId(userId);
            entity.setName(name.trim());
            entity.setType(extracted.topics().contains(name) ? TYPE_TOPIC : TYPE_CONCEPT);
            if (TYPE_CONCEPT.equals(entity.getType())) {
                try {
                    float[] vec = embeddingService.embed(name);
                    if (vec != null) {
                        entity.setEmbedding(VectorUtils.encodeToBase64(vec));
                    }
                } catch (Exception e) {
                    log.warn("实体向量化失败（名称仍入库）: name={}", name, e);
                }
            }
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            entityMapper.insert(entity);
            ids.add(entity.getId());
        }
        return ids;
    }

    /** 实体名归一化：判重用（trim + 小写 + 去内部空白），防 Redis/redis/Redis  等分裂。 */
    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase().replaceAll("\\s+", "");
    }

    /** 条目-实体关联（幂等：已有关联跳过）。 */
    public void linkItems(Long userId, Long itemId, List<Long> entityIds) {
        if (itemId == null || entityIds == null || entityIds.isEmpty()) {
            return;
        }
        for (Long entityId : entityIds) {
            QueryWrapper<KnowledgeItemEntity> wrapper = new QueryWrapper<>();
            wrapper.eq("item_id", itemId).eq("entity_id", entityId);
            if (itemEntityMapper.selectCount(wrapper) > 0) {
                continue;
            }
            KnowledgeItemEntity link = new KnowledgeItemEntity();
            link.setItemId(itemId);
            link.setEntityId(entityId);
            link.setCreatedAt(LocalDateTime.now());
            itemEntityMapper.insert(link);
        }
    }

    /** 实体列表 + 关联条目数（type 为 null 查全部；单次关联查询内存计数，避免 N+1）。 */
    public List<com.dart.dto.response.KnowledgeEntityVO> listEntities(Long userId, String type) {
        QueryWrapper<KnowledgeEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        if (type != null && !type.isBlank()) {
            wrapper.eq("type", type);
        }
        List<KnowledgeEntity> entities = entityMapper.selectList(wrapper);
        if (entities.isEmpty()) {
            return List.of();
        }
        List<Long> entityIds = entities.stream().map(KnowledgeEntity::getId).toList();
        Map<Long, Integer> countMap = new HashMap<>();
        for (KnowledgeItemEntity link : itemEntityMapper.selectList(
                new QueryWrapper<KnowledgeItemEntity>().in("entity_id", entityIds))) {
            countMap.merge(link.getEntityId(), 1, Integer::sum);
        }
        return entities.stream()
                .map(e -> new com.dart.dto.response.KnowledgeEntityVO(e.getId(), e.getName(), e.getType(),
                        countMap.getOrDefault(e.getId(), 0)))
                .toList();
    }

    /** 名称匹配：查询词 contains 实体名（大小写不敏感，实体名 ≥2 字符）→ 命中实体关联的条目 id。 */
    public Set<Long> findItemsByEntityName(Long userId, String query) {
        if (query == null || query.isBlank()) {
            return Set.of();
        }
        String lowerQuery = query.toLowerCase();
        List<KnowledgeEntity> entities = entityMapper.selectList(
                new QueryWrapper<KnowledgeEntity>().eq("user_id", userId));
        List<Long> hitEntityIds = new ArrayList<>();
        for (KnowledgeEntity e : entities) {
            String name = e.getName();
            if (name == null || name.length() < MIN_NAME_LEN
                    || FALLBACK_TOPIC.equals(name)) {
                continue;
            }
            if (lowerQuery.contains(name.toLowerCase())) {
                hitEntityIds.add(e.getId());
            }
        }
        return itemIdsOf(hitEntityIds);
    }

    /** 向量匹配：concept 实体向量与查询向量余弦 ≥ 阈值 → 命中实体关联的条目 id。 */
    public Set<Long> matchEntitiesByVector(Long userId, float[] queryVec) {
        if (queryVec == null) {
            return Set.of();
        }
        QueryWrapper<KnowledgeEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).eq("type", TYPE_CONCEPT)
                .isNotNull("embedding");
        List<Long> hitEntityIds = new ArrayList<>();
        for (KnowledgeEntity e : entityMapper.selectList(wrapper)) {
            if (e.getEmbedding() == null) {
                continue;
            }
            try {
                float[] vec = VectorUtils.decodeFromBase64(e.getEmbedding());
                if (VectorUtils.cosineSimilarity(queryVec, vec) >= ENTITY_VECTOR_THRESHOLD) {
                    hitEntityIds.add(e.getId());
                }
            } catch (Exception ex) {
                log.debug("实体向量解码失败，跳过: entityId={}", e.getId(), ex);
            }
        }
        return itemIdsOf(hitEntityIds);
    }

    private Set<Long> itemIdsOf(List<Long> entityIds) {
        if (entityIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> itemIds = new HashSet<>();
        for (KnowledgeItemEntity link : itemEntityMapper.selectList(
                new QueryWrapper<KnowledgeItemEntity>().in("entity_id", entityIds))) {
            itemIds.add(link.getItemId());
        }
        return itemIds;
    }
}
