package com.dart.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dart.dto.response.KnowledgeDetailVO;
import com.dart.dto.response.KnowledgeItemVO;
import com.dart.entity.KnowledgeChunk;
import com.dart.entity.KnowledgeItem;
import com.dart.exception.BusinessException;
import com.dart.exception.ErrorCode;
import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.mapper.KnowledgeItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeItemMapper knowledgeItemMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;

    public List<KnowledgeItemVO> listUserKnowledge(Long userId, int page, int size) {
        QueryWrapper<KnowledgeItem> query = new QueryWrapper<>();
        query.eq("user_id", userId)
                .eq("is_deleted", 0)
                .orderByDesc("created_at");
        query.last("LIMIT " + size + " OFFSET " + ((page - 1) * size));
        return knowledgeItemMapper.selectList(query).stream()
                .map(this::toVO)
                .toList();
    }

    public KnowledgeDetailVO getKnowledgeDetail(Long userId, Long knowledgeId) {
        KnowledgeItem item = knowledgeItemMapper.selectById(knowledgeId);
        if (item == null || !item.getUserId().equals(userId) || item.getIsDeleted() != 0) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_NOT_FOUND);
        }

        QueryWrapper<KnowledgeChunk> chunkQuery = new QueryWrapper<>();
        chunkQuery.eq("knowledge_item_id", knowledgeId).orderByAsc("chunk_index");
        List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectList(chunkQuery);

        return toDetailVO(item, chunks);
    }

    public void deleteKnowledge(Long userId, Long knowledgeId) {
        KnowledgeItem item = knowledgeItemMapper.selectById(knowledgeId);
        if (item == null || !item.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_NOT_FOUND);
        }
        item.setIsDeleted(1);
        knowledgeItemMapper.updateKnowledgeItem(item);
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
