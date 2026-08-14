package com.dart.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dart.dto.request.KnowledgeUpdateRequest;
import com.dart.dto.response.KnowledgeDetailVO;
import com.dart.dto.response.KnowledgeGraphDTO;
import com.dart.dto.response.KnowledgeItemVO;
import com.dart.dto.response.PageResult;
import com.dart.entity.KnowledgeChunk;
import com.dart.entity.KnowledgeItem;
import com.dart.entity.KnowledgeRelation;
import com.dart.exception.BusinessException;
import com.dart.exception.ErrorCode;
import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.mapper.KnowledgeItemMapper;
import com.dart.mapper.KnowledgeRelationMapper;
import com.dart.util.TextUtils;
import com.dart.util.VectorUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private static final String PREDICATE_RELATED = "related_to";

    private final KnowledgeItemMapper knowledgeItemMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeRelationMapper relationMapper;

    @Value("${agent.graph.link-similarity-threshold:0.75}")
    private double linkSimilarityThreshold = 0.75;

    public PageResult<KnowledgeItemVO> listUserKnowledge(Long userId, int page, int size,
                                                         String tag, String keyword) {
        Page<KnowledgeItem> p = new Page<>(page, size);
        QueryWrapper<KnowledgeItem> query = new QueryWrapper<>();
        query.eq("user_id", userId)
                .eq("is_deleted", 0)
                .orderByDesc("created_at");
        if (tag != null && !tag.isBlank()) {
            query.apply("FIND_IN_SET({0}, tags)", tag.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(q -> q.like("title", kw)
                    .or().like("tags", kw)
                    .or().like("notes", kw)
                    .or().apply("EXISTS (SELECT 1 FROM knowledge_chunk kc "
                            + "WHERE kc.knowledge_item_id = knowledge_item.id "
                            + "AND kc.chunk_text LIKE {0})", "%" + kw + "%"));
        }
        p = knowledgeItemMapper.selectPage(p, query);
        List<KnowledgeItemVO> records = p.getRecords().stream()
                .map(this::toVO)
                .toList();
        return new PageResult<>(records, p.getTotal());
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
        item.setTitle(title);
        item.setTags(tags);
        item.setNotes(req.notes());
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
                ensureItemNode(nodes, other);
            } else {
                ensureExternalNode(nodes, r.getObjectLabel());
            }
        }

        for (Long l1 : level1ItemIds) {
            for (KnowledgeRelation r : relationsOf(l1)) {
                Long other = otherSide(r, l1);
                if (other != null && other.equals(knowledgeId)) {
                    continue; // 中心节点的关系已在层 1 覆盖
                }
                addEdge(nodes, edges, r);
                if (other != null) {
                    ensureItemNode(nodes, other);
                } else {
                    ensureExternalNode(nodes, r.getObjectLabel());
                }
            }
        }
        return new KnowledgeGraphDTO(new ArrayList<>(nodes.values()), new ArrayList<>(edges.values()));
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

    private void ensureItemNode(Map<String, KnowledgeGraphDTO.GraphNode> nodes, Long itemId) {
        if (nodes.containsKey(nodeId(itemId))) {
            return;
        }
        KnowledgeItem item = knowledgeItemMapper.selectById(itemId);
        if (item != null) {
            nodes.put(nodeId(itemId),
                    new KnowledgeGraphDTO.GraphNode(nodeId(itemId), item.getTitle(), "knowledge"));
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
        Map<Long, float[]> vectors = loadFirstChunkVectors(
                items.stream().map(KnowledgeItem::getId).toList());
        for (KnowledgeItem other : items) {
            if (other.getId().equals(newItemId)) {
                continue;
            }
            linkPair(self, other, vectors.get(newItemId), vectors.get(other.getId()));
        }
    }

    /**
     * 手动补建：知识库全部条目两两计算并建关系（幂等，重复执行跳过已有关系）。
     *
     * @return 本次新增的关系条数
     */
    public int rebuildLinks(Long userId) {
        List<KnowledgeItem> items = loadItems(userId);
        if (items.size() < 2) {
            return 0;
        }
        Map<Long, float[]> vectors = loadFirstChunkVectors(
                items.stream().map(KnowledgeItem::getId).toList());
        int created = 0;
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                if (linkPair(items.get(i), items.get(j),
                        vectors.get(items.get(i).getId()), vectors.get(items.get(j).getId()))) {
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

    private boolean linkPair(KnowledgeItem a, KnowledgeItem b, float[] vecA, float[] vecB) {
        if (a.getId().equals(b.getId())) {
            return false;
        }
        String source;
        // 标题互相包含（排除同名）+ 向量余弦 ≥ 阈值，双通道并集
        if (TextUtils.containsIgnoreCase(a.getTitle(), b.getTitle())
                && !a.getTitle().equalsIgnoreCase(b.getTitle())) {
            source = "TITLE_MATCH@item_id=" + a.getId();
        } else if (vecA != null && vecB != null
                && VectorUtils.cosineSimilarity(vecA, vecB) >= linkSimilarityThreshold) {
            source = "VECTOR_SIMILARITY@item_id=" + a.getId();
        } else {
            return false;
        }
        if (relationExists(a.getId(), b.getId())) {
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
        return true;
    }

    private boolean relationExists(Long idA, Long idB) {
        QueryWrapper<KnowledgeRelation> wrapper = new QueryWrapper<>();
        wrapper.eq("subject_id", idA).eq("object_id", idB)
                .or().eq("subject_id", idB).eq("object_id", idA);
        return relationMapper.selectCount(wrapper) > 0;
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
        return vo;
    }
}
