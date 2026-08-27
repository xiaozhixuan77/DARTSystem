package com.dart.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dart.dto.request.KnowledgeUpdateRequest;
import com.dart.dto.response.KnowledgeDetailVO;
import com.dart.dto.response.KnowledgeGraphDTO;
import com.dart.dto.response.KnowledgeItemVO;
import com.dart.dto.response.PageResult;
import com.dart.entity.KnowledgeChunk;
import com.dart.entity.KnowledgeEntity;
import com.dart.entity.KnowledgeItem;
import com.dart.entity.KnowledgeItemEntity;
import com.dart.entity.KnowledgeRelation;
import com.dart.exception.BusinessException;
import com.dart.exception.ErrorCode;
import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.mapper.KnowledgeEntityMapper;
import com.dart.mapper.KnowledgeItemEntityMapper;
import com.dart.mapper.KnowledgeItemMapper;
import com.dart.mapper.KnowledgeRelationMapper;
import com.dart.util.TextUtils;
import com.dart.util.VectorUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private static final String PREDICATE_RELATED = "related_to";
    private static final int MAX_CONTENT_LENGTH = 200_000;

    /** 沉淀合并相似度阈值：新报告与现有条目首块向量余弦 ≥ 此值视为近似重复，合并更新而非新增。 */
    private static final double SEDIMENT_MERGE_SIMILARITY = 0.85;

    private final KnowledgeItemMapper knowledgeItemMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeRelationMapper relationMapper;
    private final EmbeddingService embeddingService;
    private final EntityService entityService;
    private final EntityExtractionService entityExtractionService;
    private final KnowledgeEntityMapper entityMapper;
    private final KnowledgeItemEntityMapper itemEntityMapper;

    @Value("${agent.graph.link-similarity-threshold:0.75}")
    private double linkSimilarityThreshold = 0.75;

    public PageResult<KnowledgeItemVO> listUserKnowledge(Long userId, int page, int size,
                                                         String tag, String keyword, String entity) {
        Page<KnowledgeItem> p = new Page<>(page, size);
        QueryWrapper<KnowledgeItem> query = new QueryWrapper<>();
        query.eq("user_id", userId)
                .eq("is_deleted", 0)
                .orderByDesc("created_at");
        if (tag != null && !tag.isBlank()) {
            query.apply("FIND_IN_SET({0}, tags)", tag.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = escapeLikeKeyword(keyword.trim());
            query.and(q -> q.like("title", kw)
                    .or().like("tags", kw)
                    .or().like("notes", kw)
                    .or().apply("EXISTS (SELECT 1 FROM knowledge_chunk kc "
                            + "WHERE kc.knowledge_item_id = knowledge_item.id "
                            + "AND kc.chunk_text LIKE {0})", "%" + kw + "%"));
        }
        if (entity != null && !entity.isBlank()) {
            query.apply("EXISTS (SELECT 1 FROM knowledge_item_entity kie "
                    + "JOIN knowledge_entity ke ON kie.entity_id = ke.id "
                    + "WHERE kie.item_id = knowledge_item.id "
                    + "AND ke.user_id = {0} AND ke.name = {1})", userId, entity.trim());
        }
        p = knowledgeItemMapper.selectPage(p, query);
        List<KnowledgeItemVO> records = p.getRecords().stream()
                .map(this::toVO)
                .toList();
        return new PageResult<>(records, p.getTotal());
    }

    /** 实体列表（委托 EntityService）：知识库页实体云/筛选数据源。 */
    public List<com.dart.dto.response.KnowledgeEntityVO> listEntities(Long userId) {
        return entityService.listEntities(userId, null).stream()
                .map(v -> new com.dart.dto.response.KnowledgeEntityVO(v.id(), v.name(), v.type(), v.itemCount()))
                .toList();
    }

    /** MySQL LIKE 通配符转义（默认 ESCAPE '\'）：防 %/_ 篡改匹配语义（先转义反斜杠再转义通配符） */
    private static String escapeLikeKeyword(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public KnowledgeDetailVO getKnowledgeDetail(Long userId, Long knowledgeId) {
        KnowledgeItem item = knowledgeItemMapper.selectById(knowledgeId);
        if (item == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_NOT_FOUND);
        }
        if (!item.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        QueryWrapper<KnowledgeChunk> chunkQuery = new QueryWrapper<>();
        chunkQuery.eq("knowledge_item_id", knowledgeId).orderByAsc("chunk_index");
        List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectList(chunkQuery);

        return toDetailVO(item, chunks);
    }

    public void deleteKnowledge(Long userId, Long knowledgeId) {
        KnowledgeItem item = knowledgeItemMapper.selectById(knowledgeId);
        if (item == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_NOT_FOUND);
        }
        if (!item.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        knowledgeItemMapper.deleteById(item.getId());
    }

    @Transactional
    public void updateKnowledge(Long userId, Long knowledgeId, KnowledgeUpdateRequest req) {
        KnowledgeItem item = knowledgeItemMapper.selectById(knowledgeId);
        if (item == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_NOT_FOUND);
        }
        if (!item.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        String title = req.title() == null ? "" : req.title().trim();
        if (title.isEmpty() || title.length() > 500) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        String tags = normalizeTags(req.tags());
        if (tags.length() > 500) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        if (req.content() != null && req.content().length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        boolean contentChanged = req.content() != null && !req.content().equals(item.getContent());
        boolean titleChanged = !title.equals(item.getTitle());
        if (contentChanged) {
            item.setContent(req.content());
        }
        item.setTitle(title);
        item.setTags(tags);
        item.setNotes(req.notes());
        item.setUpdatedAt(LocalDateTime.now());
        knowledgeItemMapper.updateById(item);
        if (contentChanged) {
            // 同步删除旧 chunk（事务内）：旧向量立即失效，RAG 不再命中旧内容
            QueryWrapper<KnowledgeChunk> wrapper = new QueryWrapper<>();
            wrapper.eq("knowledge_item_id", knowledgeId);
            knowledgeChunkMapper.delete(wrapper);
            // 异步重建：embedding 远程调用不阻塞保存响应
            String newContent = req.content();
            runAfterCommit(() -> Thread.startVirtualThread(() -> {
                try {
                    embeddingService.rebuildChunksForItem(knowledgeId, newContent);
                } catch (Exception e) {
                    log.warn("正文重建失败: itemId={}", knowledgeId, e);
                }
            }));
        }
        if (contentChanged || titleChanged) {
            runAfterCommit(() -> Thread.startVirtualThread(() -> {
                try {
                    rebuildLinks(userId);
                } catch (Exception e) {
                    log.warn("编辑后关联重算失败: userId={}", userId, e);
                }
            }));
        }
    }

    private void runAfterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
        } else {
            action.run();
        }
    }

    /**
     * 沉淀合并判定：新报告与现有条目是否存在近似重复（标题关键词完全相等 或 首块向量余弦 ≥ 0.85）。
     * 同主题不同内容（0.55-0.65）不合并——知识库保留内容多样性。
     */
    public Optional<KnowledgeItem> findSimilarItem(Long userId, String title, String content) {
        List<KnowledgeItem> items = loadItems(userId);
        if (items.isEmpty()) {
            return Optional.empty();
        }
        List<String> titleKeywords = TextUtils.extractKeywords(title);
        Map<Long, float[]> vectors = loadFirstChunkVectors(
                items.stream().map(KnowledgeItem::getId).toList());
        // 新内容向量化（远程调用，失败则仅标题通道）
        float[] contentVec = null;
        if (content != null && !content.isBlank()) {
            try {
                contentVec = embeddingService.embed(content);
            } catch (Exception e) {
                log.warn("沉淀合并向量化失败，仅标题通道: userId={}", userId, e);
            }
        }
        for (KnowledgeItem item : items) {
            if (!titleKeywords.isEmpty()
                    && titleKeywords.equals(TextUtils.extractKeywords(item.getTitle()))) {
                return Optional.of(item);
            }
            float[] vec = vectors.get(item.getId());
            if (contentVec != null && vec != null
                    && VectorUtils.cosineSimilarity(contentVec, vec) >= SEDIMENT_MERGE_SIMILARITY) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    /**
     * 沉淀合并：更新既有条目的标题与正文（保留用户可能编辑过的 tags/source_url），由调用方重建 chunk。
     */
    @Transactional
    public void mergeItem(Long userId, Long itemId, String newTitle, String newContent) {
        KnowledgeItem item = knowledgeItemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_NOT_FOUND);
        }
        if (!item.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        item.setTitle(newTitle);
        item.setContent(newContent);
        item.setUpdatedAt(LocalDateTime.now());
        knowledgeItemMapper.updateById(item);
    }

    /**
     * 标签规范化：逗号拆分 → trim → 去空去重 → 无空格逗号连接（配合 FIND_IN_SET 精确匹配）。
     */
    private static String normalizeTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        List<String> tags = new ArrayList<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty() && !tags.contains(t)) {
                tags.add(t);
            }
        }
        return String.join(",", tags);
    }

    public List<String> aggregateTags(Long userId) {
        QueryWrapper<KnowledgeItem> query = new QueryWrapper<>();
        query.eq("user_id", userId).eq("is_deleted", 0);
        Set<String> tags = new LinkedHashSet<>();
        for (KnowledgeItem item : knowledgeItemMapper.selectList(query)) {
            if (item.getTags() == null || item.getTags().isBlank()) {
                continue;
            }
            for (String part : item.getTags().split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    tags.add(t);
                }
            }
        }
        return new ArrayList<>(tags).stream().sorted().toList();
    }

    /**
     * 知识关系图（BFS 2 层）：中心条目 → 直接关联（subject/object 双向）→ 一级条目的二级关联。
     * 节点 id：知识条目 "item:{id}"，外部概念 "ext:{label}"。
     */
    public KnowledgeGraphDTO getGraphData(Long userId, Long knowledgeId) {
        KnowledgeItem center = knowledgeItemMapper.selectById(knowledgeId);
        if (center == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_NOT_FOUND);
        }
        if (!center.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        Map<String, KnowledgeGraphDTO.GraphNode> nodes = new LinkedHashMap<>();
        Map<String, KnowledgeGraphDTO.GraphEdge> edges = new LinkedHashMap<>();
        nodes.put(nodeId(knowledgeId), new KnowledgeGraphDTO.GraphNode(
                nodeId(knowledgeId), center.getTitle(), "knowledge"));

        List<KnowledgeRelation> level1 = relationsOf(knowledgeId);
        Set<Long> level1ItemIds = new LinkedHashSet<>();
        for (KnowledgeRelation r : level1) {
            addEdge(nodes, edges, r);
            Long other = otherSide(r, knowledgeId);
            if (other != null) {
                level1ItemIds.add(other);
            } else {
                ensureExternalNode(nodes, r.getObjectLabel());
            }
        }
        ensureItemNodes(nodes, level1ItemIds);

        // 层 2 批量加载：全部一级条目一次查询（原来逐节点 relationsOf → N+1）
        List<KnowledgeRelation> level2Relations = level1ItemIds.isEmpty() ? List.of()
                : relationMapper.selectList(new QueryWrapper<KnowledgeRelation>()
                        .in("subject_id", level1ItemIds).or().in("object_id", level1ItemIds));
        Set<Long> level2ItemIds = new LinkedHashSet<>();
        for (Long l1 : level1ItemIds) {
            for (KnowledgeRelation r : level2Relations) {
                if (!involves(r, l1)) {
                    continue;
                }
                Long other = otherSide(r, l1);
                if (other != null && other.equals(knowledgeId)) {
                    continue; // 中心节点的关系已在层 1 覆盖
                }
                addEdge(nodes, edges, r);
                if (other != null) {
                    level2ItemIds.add(other);
                } else {
                    ensureExternalNode(nodes, r.getObjectLabel());
                }
            }
        }
        ensureItemNodes(nodes, level2ItemIds);

        // 中心条目关联实体节点（entity 类别）
        addEntityNodes(userId, Set.of(knowledgeId), nodes, edges);
        return new KnowledgeGraphDTO(new ArrayList<>(nodes.values()), new ArrayList<>(edges.values()));
    }

    private static boolean involves(KnowledgeRelation r, Long itemId) {
        return (r.getSubjectId() != null && r.getSubjectId().equals(itemId))
                || (r.getObjectId() != null && r.getObjectId().equals(itemId));
    }

    /**
     * 全局知识图谱：全部条目为节点，条目间关联为边（外部概念 object_id=null 不进入，
     * 避免节点爆炸；单条目下钻图仍可见）。
     */
    public KnowledgeGraphDTO getGlobalGraph(Long userId) {
        List<KnowledgeItem> items = loadItems(userId);
        if (items.isEmpty()) {
            return new KnowledgeGraphDTO(List.of(), List.of());
        }
        Set<Long> itemIds = items.stream().map(KnowledgeItem::getId).collect(Collectors.toSet());
        Map<String, KnowledgeGraphDTO.GraphNode> nodes = new LinkedHashMap<>();
        Map<String, KnowledgeGraphDTO.GraphEdge> edges = new LinkedHashMap<>();
        for (KnowledgeItem item : items) {
            nodes.put(nodeId(item.getId()),
                    new KnowledgeGraphDTO.GraphNode(nodeId(item.getId()), item.getTitle(), "knowledge"));
        }
        QueryWrapper<KnowledgeRelation> wrapper = new QueryWrapper<>();
        wrapper.in("subject_id", itemIds);
        for (KnowledgeRelation r : relationMapper.selectList(wrapper)) {
            if (r.getObjectId() == null || !itemIds.contains(r.getObjectId())) {
                continue;
            }
            addEdge(nodes, edges, r);
        }
        addEntityNodes(userId, itemIds, nodes, edges);
        return new KnowledgeGraphDTO(new ArrayList<>(nodes.values()), new ArrayList<>(edges.values()));
    }

    /**
     * 实体节点加入全局图谱：条目 ↔ 实体边（label 取实体 type）。
     * 实体单独成节点（category=entity），前端第三色渲染。
     */
    private void addEntityNodes(Long userId, Set<Long> itemIds,
                                Map<String, KnowledgeGraphDTO.GraphNode> nodes,
                                Map<String, KnowledgeGraphDTO.GraphEdge> edges) {
        if (itemIds.isEmpty()) {
            return;
        }
        List<KnowledgeEntity> entities = entityMapper.selectList(
                new QueryWrapper<KnowledgeEntity>().eq("user_id", userId));
        if (entities.isEmpty()) {
            return;
        }
        Map<Long, KnowledgeEntity> entityById = entities.stream()
                .collect(Collectors.toMap(KnowledgeEntity::getId, e -> e));
        List<KnowledgeItemEntity> links = itemEntityMapper.selectList(
                new QueryWrapper<KnowledgeItemEntity>().in("item_id", itemIds));
        for (KnowledgeItemEntity link : links) {
            KnowledgeEntity entity = entityById.get(link.getEntityId());
            if (entity == null) {
                continue;
            }
            String entityNodeId = "ent:" + entity.getId();
            nodes.putIfAbsent(entityNodeId,
                    new KnowledgeGraphDTO.GraphNode(entityNodeId, entity.getName(), "entity"));
            String key = nodeId(link.getItemId()) + "|" + entityNodeId;
            edges.putIfAbsent(key, new KnowledgeGraphDTO.GraphEdge(
                    nodeId(link.getItemId()), entityNodeId, entity.getType()));
        }
    }

    private List<KnowledgeRelation> relationsOf(Long itemId) {
        QueryWrapper<KnowledgeRelation> wrapper = new QueryWrapper<>();
        wrapper.eq("subject_id", itemId).or().eq("object_id", itemId);
        return relationMapper.selectList(wrapper);
    }

    private static Long otherSide(KnowledgeRelation r, Long knownId) {
        if (r.getSubjectId() != null && !r.getSubjectId().equals(knownId)) {
            return r.getSubjectId();
        }
        if (r.getObjectId() != null && !r.getObjectId().equals(knownId)) {
            return r.getObjectId();
        }
        return null;
    }

    private void addEdge(Map<String, KnowledgeGraphDTO.GraphNode> nodes,
                         Map<String, KnowledgeGraphDTO.GraphEdge> edges,
                         KnowledgeRelation r) {
        String source = nodeId(r.getSubjectId());
        String target = r.getObjectId() != null ? nodeId(r.getObjectId())
                : "ext:" + r.getObjectLabel();
        String key = source + "|" + target + "|" + r.getPredicate();
        edges.putIfAbsent(key, new KnowledgeGraphDTO.GraphEdge(source, target, r.getPredicate()));
    }

    /** 批量补齐条目节点：一次 selectBatchIds 替代逐节点 selectById（防 N+1） */
    private void ensureItemNodes(Map<String, KnowledgeGraphDTO.GraphNode> nodes,
                                 Collection<Long> itemIds) {
        List<Long> missing = itemIds.stream()
                .filter(id -> !nodes.containsKey(nodeId(id)))
                .distinct().toList();
        if (missing.isEmpty()) {
            return;
        }
        for (KnowledgeItem item : knowledgeItemMapper.selectBatchIds(missing)) {
            if (item != null) {
                nodes.put(nodeId(item.getId()),
                        new KnowledgeGraphDTO.GraphNode(nodeId(item.getId()), item.getTitle(), "knowledge"));
            }
        }
    }

    private static void ensureExternalNode(Map<String, KnowledgeGraphDTO.GraphNode> nodes,
                                           String label) {
        if (label != null && !label.isBlank()) {
            nodes.putIfAbsent("ext:" + label,
                    new KnowledgeGraphDTO.GraphNode("ext:" + label, label, "external"));
        }
    }

    private static String nodeId(Long itemId) {
        return "item:" + itemId;
    }

    // === 跨条目关联（双通道：标题子串 + 向量相似度）===

    /**
     * 新沉淀条目与知识库现有条目建立关联（增量通道，沉淀后调用）。
     */
    public void linkRelatedItems(Long userId, Long newItemId) {
        KnowledgeItem self = knowledgeItemMapper.selectById(newItemId);
        if (self == null || self.getTitle() == null || self.getTitle().isBlank()) {
            return;
        }
        List<KnowledgeItem> items = loadItems(userId);
        Set<String> existingPairs = loadExistingRelations(
                items.stream().map(KnowledgeItem::getId).toList());
        Map<Long, float[]> vectors = loadFirstChunkVectors(
                items.stream().map(KnowledgeItem::getId).toList());
        for (KnowledgeItem other : items) {
            if (other.getId().equals(newItemId)) {
                continue;
            }
            linkPair(self, other, vectors.get(newItemId), vectors.get(other.getId()), existingPairs);
        }
    }

    /**
     * 手动补建：知识库全部条目两两计算并建关系（幂等，重复执行跳过已有关系）。
     *
     * @return 本次新增的关系条数
     */
    /**
     * 存量条目实体回填：实体化功能上线前沉淀的条目无实体关联，遍历补齐。
     * 已有关联的条目跳过（幂等）；extract 失败/无实体跳过该条目。
     * 返回实际处理（新建立关联）的条目数。
     */
    public int rebuildEntities(Long userId) {
        List<KnowledgeItem> items = loadItems(userId);
        if (items.isEmpty()) {
            return 0;
        }
        List<Long> itemIds = items.stream().map(KnowledgeItem::getId).toList();
        Set<Long> linkedItemIds = itemEntityMapper.selectList(
                        new QueryWrapper<KnowledgeItemEntity>().in("item_id", itemIds))
                .stream().map(KnowledgeItemEntity::getItemId).collect(Collectors.toSet());
        int processed = 0;
        for (KnowledgeItem item : items) {
            if (linkedItemIds.contains(item.getId())) {
                continue;
            }
            EntityExtractionService.ExtractedEntities extracted =
                    entityExtractionService.extract(item.getTitle(), item.getContent());
            if (extracted.isEmpty()) {
                continue;
            }
            List<Long> entityIds = entityService.upsertEntities(userId, extracted);
            entityService.linkItems(userId, item.getId(), entityIds);
            processed++;
        }
        log.info("存量条目实体回填完成: userId={}, processed={}", userId, processed);
        return processed;
    }

    public int rebuildLinks(Long userId) {
        List<KnowledgeItem> items = loadItems(userId);
        if (items.size() < 2) {
            return 0;
        }
        Map<Long, float[]> vectors = loadFirstChunkVectors(
                items.stream().map(KnowledgeItem::getId).toList());
        // 现存关系一次预载（原来每对 selectCount → O(n²) 查询），内存集合判断
        Set<String> existingPairs = loadExistingRelations(
                items.stream().map(KnowledgeItem::getId).toList());
        int created = 0;
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                if (linkPair(items.get(i), items.get(j),
                        vectors.get(items.get(i).getId()), vectors.get(items.get(j).getId()),
                        existingPairs)) {
                    created++;
                }
            }
        }
        return created;
    }

    private List<KnowledgeItem> loadItems(Long userId) {
        QueryWrapper<KnowledgeItem> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).eq("is_deleted", 0);
        return knowledgeItemMapper.selectList(wrapper);
    }

    /**
     * 每条目取首块（chunk_index 最小）向量；首个出现的 chunk 即 index 最小的块。
     * 只查指定条目（限定当前用户，避免全表扫描）。
     */
    private Map<Long, float[]> loadFirstChunkVectors(List<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, float[]> vectors = new HashMap<>();
        QueryWrapper<KnowledgeChunk> wrapper = new QueryWrapper<>();
        wrapper.in("knowledge_item_id", itemIds).orderByAsc("chunk_index");
        for (KnowledgeChunk c : knowledgeChunkMapper.selectList(wrapper)) {
            if (c.getEmbedding() == null) {
                continue;
            }
            try {
                float[] vec = VectorUtils.bytesToFloatArray(Base64.getDecoder().decode(c.getEmbedding()));
                vectors.putIfAbsent(c.getKnowledgeItemId(), vec);
            } catch (RuntimeException e) {
                log.warn("chunk 向量解码失败，跳过: chunkId={}", c.getId());
            }
        }
        return vectors;
    }

    private boolean linkPair(KnowledgeItem a, KnowledgeItem b, float[] vecA, float[] vecB,
                             Set<String> existingPairs) {
        if (a.getId().equals(b.getId())) {
            return false;
        }
        String source;
        // 三通道并集：标题完整包含（排除同名）→ 标题关键词共享（疑问词/标点剔除后 ≥1 共享词）→ 向量余弦 ≥ 阈值
        if (TextUtils.containsIgnoreCase(a.getTitle(), b.getTitle())
                && !a.getTitle().equalsIgnoreCase(b.getTitle())) {
            source = "TITLE_MATCH@item_id=" + a.getId();
        } else if (!a.getTitle().equalsIgnoreCase(b.getTitle()) && shareTitleKeyword(a, b)) {
            source = "TITLE_KEYWORD@item_id=" + a.getId();
        } else if (vecA != null && vecB != null
                && VectorUtils.cosineSimilarity(vecA, vecB) >= linkSimilarityThreshold) {
            source = "VECTOR_SIMILARITY@item_id=" + a.getId();
        } else {
            return false;
        }
        if (existingPairs.contains(pairKey(a.getId(), b.getId()))) {
            return false;
        }
        KnowledgeRelation r = new KnowledgeRelation();
        r.setSubjectId(a.getId());
        r.setPredicate(PREDICATE_RELATED);
        r.setObjectId(b.getId());
        r.setConfidence(1.0);
        r.setSource(source);
        r.setCreatedAt(LocalDateTime.now());
        relationMapper.insert(r);
        existingPairs.add(pairKey(a.getId(), b.getId()));
        return true;
    }

    /**
     * 标题关键词共享判定：两标题提取关键词后共享 ≥1 个（疑问词/标点已剔除）。
     */
    private static boolean shareTitleKeyword(KnowledgeItem a, KnowledgeItem b) {
        List<String> kwA = TextUtils.extractKeywords(a.getTitle());
        List<String> kwB = TextUtils.extractKeywords(b.getTitle());
        if (kwA.isEmpty() || kwB.isEmpty()) {
            return false;
        }
        return kwA.stream().anyMatch(kwB::contains);
    }

    /** 批量预载条目间现存关系（subject/object 任一方向），返回规范化 pair key 集合 */
    private Set<String> loadExistingRelations(Collection<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return Set.of();
        }
        Set<String> pairs = new HashSet<>();
        QueryWrapper<KnowledgeRelation> wrapper = new QueryWrapper<>();
        wrapper.in("subject_id", itemIds).or().in("object_id", itemIds);
        for (KnowledgeRelation r : relationMapper.selectList(wrapper)) {
            if (r.getSubjectId() != null && r.getObjectId() != null) {
                pairs.add(pairKey(r.getSubjectId(), r.getObjectId()));
            }
        }
        return pairs;
    }

    private static String pairKey(Long a, Long b) {
        return a < b ? a + "|" + b : b + "|" + a;
    }

    public boolean existsBySourceUrl(String sourceUrlHash) {
        QueryWrapper<KnowledgeItem> query = new QueryWrapper<>();
        query.eq("source_url_hash", sourceUrlHash).eq("is_deleted", 0);
        return knowledgeItemMapper.selectCount(query) > 0;
    }

    private KnowledgeItemVO toVO(KnowledgeItem item) {
        KnowledgeItemVO vo = new KnowledgeItemVO();
        vo.setId(item.getId());
        vo.setTitle(item.getTitle());
        vo.setSourceUrl(item.getSourceUrl());
        vo.setTags(item.getTags());
        vo.setScoreT(item.getScoreT());
        vo.setScoreA(item.getScoreA());
        vo.setScoreD(item.getScoreD());
        vo.setScoreM(item.getScoreM());
        vo.setWeightedScore(item.getWeightedScore());
        vo.setCreatedAt(item.getCreatedAt());
        return vo;
    }

    private KnowledgeDetailVO toDetailVO(KnowledgeItem item, List<KnowledgeChunk> chunks) {
        KnowledgeDetailVO vo = new KnowledgeDetailVO();
        vo.setId(item.getId());
        vo.setTitle(item.getTitle());
        vo.setContent(item.getContent());
        vo.setSourceUrl(item.getSourceUrl());
        vo.setTags(item.getTags());
        vo.setNotes(item.getNotes());
        vo.setScoreT(item.getScoreT());
        vo.setScoreA(item.getScoreA());
        vo.setScoreD(item.getScoreD());
        vo.setScoreM(item.getScoreM());
        vo.setWeightedScore(item.getWeightedScore());
        vo.setCreatedAt(item.getCreatedAt());
        vo.setUpdatedAt(item.getUpdatedAt());
        vo.setChunks(chunks.stream()
                .map(c -> new KnowledgeDetailVO.ChunkVO(c.getChunkIndex(), c.getChunkText()))
                .toList());
        vo.setEntityNames(loadEntityNames(item.getId()));
        return vo;
    }

    private List<String> loadEntityNames(Long itemId) {
        List<KnowledgeItemEntity> links = itemEntityMapper.selectList(
                new QueryWrapper<KnowledgeItemEntity>().eq("item_id", itemId));
        if (links.isEmpty()) {
            return List.of();
        }
        List<KnowledgeEntity> entities = entityMapper.selectBatchIds(
                links.stream().map(KnowledgeItemEntity::getEntityId).toList());
        return entities.stream().map(KnowledgeEntity::getName).toList();
    }
}
