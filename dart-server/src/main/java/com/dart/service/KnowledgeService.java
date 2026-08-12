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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeItemMapper knowledgeItemMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeRelationMapper relationMapper;

    public PageResult<KnowledgeItemVO> listUserKnowledge(Long userId, int page, int size, String tag) {
        Page<KnowledgeItem> p = new Page<>(page, size);
        QueryWrapper<KnowledgeItem> query = new QueryWrapper<>();
        query.eq("user_id", userId)
                .eq("is_deleted", 0)
                .orderByDesc("created_at");
        if (tag != null && !tag.isBlank()) {
            query.apply("FIND_IN_SET({0}, tags)", tag.trim());
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
